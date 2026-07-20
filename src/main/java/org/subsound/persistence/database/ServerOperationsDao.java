package org.subsound.persistence.database;

import org.subsound.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access for the {@code server_operations_queue}: the persistent, ordered queue of server-side
 * operations (star/unstar) recorded while offline and replayed once connectivity returns.
 *
 * <p>Isolates all queue SQL behind a single concept; obtained via
 * {@link DatabaseService#serverOperations()}.
 */
public class ServerOperationsDao {
    private static final Logger logger = LoggerFactory.getLogger(ServerOperationsDao.class);

    private final Database database;

    public ServerOperationsDao(Database database) {
        this.database = database;
    }

    /**
     * Enqueue a new PENDING operation. Returns the generated auto-increment id (the replay order key).
     */
    public long enqueue(UUID serverId, ServerOperationType type, ServerOperationPayload payload, Instant createdAt) {
        String sql = """
                INSERT INTO server_operations_queue
                    (server_id, operation_type, payload, status, created_at_ms)
                VALUES (?, ?, ?, 'PENDING', ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, serverId.toString());
            pstmt.setString(2, type.name());
            pstmt.setString(3, Utils.toJson(payload));
            pstmt.setLong(4, createdAt.toEpochMilli());
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new SQLException("No generated key returned for enqueued operation");
        } catch (SQLException e) {
            logger.error("Failed to enqueue server operation type={}", type, e);
            throw new RuntimeException("Failed to enqueue server operation", e);
        }
    }

    /**
     * List PENDING operations for the server in replay ({@code id}) order.
     */
    public List<ServerOperation> listPending(UUID serverId) {
        List<ServerOperation> ops = new ArrayList<>();
        String sql = "SELECT * FROM server_operations_queue WHERE server_id = ? AND status = 'PENDING' ORDER BY id ASC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ops.add(mapResultSetToServerOperation(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list pending server operations", e);
            throw new RuntimeException("Failed to list pending server operations", e);
        }
        return ops;
    }

    /** Look up a single operation by its id, regardless of status. */
    public Optional<ServerOperation> findById(long id) {
        String sql = "SELECT * FROM server_operations_queue WHERE id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToServerOperation(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load server operation: id={}", id, e);
            throw new RuntimeException("Failed to load server operation", e);
        }
        return Optional.empty();
    }

    /**
     * Mark an operation as successfully completed. Sets both the last-attempt time and the
     * completion time, satisfying the {@code completed_at set iff COMPLETED} invariant.
     */
    public void markCompleted(long id, Instant executedAt, Instant completedAt) {
        String sql = "UPDATE server_operations_queue SET status = 'COMPLETED', executed_at_ms = ?, completed_at_ms = ? WHERE id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, executedAt.toEpochMilli());
            pstmt.setLong(2, completedAt.toEpochMilli());
            pstmt.setLong(3, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark server operation completed: id={}", id, e);
            throw new RuntimeException("Failed to mark server operation completed", e);
        }
    }

    /**
     * Mark an operation as failed (non-recoverable server rejection). Records the attempt time and
     * leaves {@code completed_at} null; the op will not be retried automatically.
     */
    public void markFailed(long id, Instant executedAt) {
        String sql = "UPDATE server_operations_queue SET status = 'FAILED', executed_at_ms = ? WHERE id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, executedAt.toEpochMilli());
            pstmt.setLong(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to mark server operation failed: id={}", id, e);
            throw new RuntimeException("Failed to mark server operation failed", e);
        }
    }

    /**
     * Record an attempt that neither completed nor permanently failed (e.g. we went offline
     * mid-replay). Updates {@code executed_at} while keeping the row PENDING for a later retry.
     */
    public void touchExecuted(long id, Instant executedAt) {
        String sql = "UPDATE server_operations_queue SET executed_at_ms = ? WHERE id = ? AND status = 'PENDING'";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, executedAt.toEpochMilli());
            pstmt.setLong(2, id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to touch server operation: id={}", id, e);
            throw new RuntimeException("Failed to touch server operation", e);
        }
    }

    private ServerOperation mapResultSetToServerOperation(ResultSet rs) throws SQLException {
        var type = ServerOperationType.valueOf(rs.getString("operation_type"));
        var payload = Utils.fromJson(rs.getString("payload"), type.payloadClass());

        long executedAtMs = rs.getLong("executed_at_ms");
        Optional<Instant> executedAt = rs.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(executedAtMs));

        long completedAtMs = rs.getLong("completed_at_ms");
        Optional<Instant> completedAt = rs.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(completedAtMs));

        return new ServerOperation(
                rs.getLong("id"),
                UUID.fromString(rs.getString("server_id")),
                type,
                payload,
                ServerOperationStatus.valueOf(rs.getString("status")),
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                executedAt,
                completedAt
        );
    }
}
