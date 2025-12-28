package com.github.subsound.persistence.database;

import com.github.subsound.integration.ServerClient.ServerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);
    private final Database database;

    public DatabaseService(Database database) {
        this.database = database;
    }

    public void insert(Server server) {
        String sql = "INSERT INTO servers (id, is_primary, server_type, server_url, username, created_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, server.id().toString());
            pstmt.setBoolean(2, server.isPrimary());
            pstmt.setString(3, server.serverType().name());
            pstmt.setString(4, server.serverUrl());
            pstmt.setString(5, server.username());
            pstmt.setLong(6, server.createdAt().toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert server", e);
            throw new RuntimeException("Failed to insert server", e);
        }
    }

    public Optional<Server> getDefaultServer() {
        String sql = "SELECT id, is_primary, server_type, server_url, username, created_at FROM servers WHERE is_primary = 1 LIMIT 1";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapResultSetToServer(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to get default server", e);
            throw new RuntimeException("Failed to get default server", e);
        }
        return Optional.empty();
    }

    public Optional<Server> getServerById(String id) {
        String sql = "SELECT id, is_primary, server_type, server_url, username, created_at FROM servers WHERE id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToServer(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get server by id: {}", id, e);
            throw new RuntimeException("Failed to get server by id", e);
        }
        return Optional.empty();
    }

    public List<Server> listServers() {
        List<Server> servers = new ArrayList<>();
        String sql = "SELECT id, is_primary, server_type, server_url, username, created_at FROM servers";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                servers.add(mapResultSetToServer(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to list servers", e);
            throw new RuntimeException("Failed to list servers", e);
        }
        return servers;
    }

    private Server mapResultSetToServer(ResultSet rs) throws SQLException {
        return new Server(
                UUID.fromString(rs.getString("id")),
                rs.getBoolean("is_primary"),
                ServerType.valueOf(rs.getString("server_type")),
                rs.getString("server_url"),
                rs.getString("username"),
                Instant.ofEpochMilli(rs.getLong("created_at"))
        );
    }
}
