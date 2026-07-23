package org.subsound.persistence.database;

import org.subsound.integration.ServerClient.PlaylistKind;
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

/**
 * Data access for {@code offline_playlists}: the playlists (and the synthetic Starred playlist,
 * stored under {@link #STARRED_SENTINEL}) that the user has marked "available offline".
 *
 * <p>Presence of a row means enabled. Toggling off deletes the row (downloads are left intact).
 * {@code watermark_ms} is the last-synced high-water mark; NULL forces a full resync on the next
 * sync pass. Obtained via {@link DatabaseService#offlinePlaylists()}.
 */
public class OfflinePlaylistDao {
    private static final Logger logger = LoggerFactory.getLogger(OfflinePlaylistDao.class);

    /** Sentinel playlist id for the synthetic Starred playlist (which has no real server id). */
    public static final String STARRED_SENTINEL = "__starred__";

    private final Database database;

    public OfflinePlaylistDao(Database database) {
        this.database = database;
    }

    public record OfflinePlaylist(
            String playlistId,
            UUID serverId,
            PlaylistKind kind,
            Optional<Instant> watermark,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * Mark a playlist offline-enabled. Upserts the row and resets {@code watermark_ms} to NULL so
     * the next sync fully re-fetches and re-enqueues everything (the on&rarr;off&rarr;on repair gesture).
     */
    public void enable(UUID serverId, String playlistId, PlaylistKind kind, Instant now) {
        String sql = """
                INSERT INTO offline_playlists
                    (playlist_id, server_id, kind, watermark_ms, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, NULL, ?, ?)
                ON CONFLICT(playlist_id, server_id) DO UPDATE SET
                    kind = excluded.kind,
                    watermark_ms = NULL,
                    updated_at_ms = excluded.updated_at_ms
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, serverId.toString());
            pstmt.setString(3, kind.name());
            pstmt.setLong(4, now.toEpochMilli());
            pstmt.setLong(5, now.toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to enable offline playlist: {}", playlistId, e);
            throw new RuntimeException("Failed to enable offline playlist", e);
        }
    }

    /** Remove the offline flag for a playlist. Downloads in {@code download_queue} are untouched. */
    public void disable(UUID serverId, String playlistId) {
        String sql = "DELETE FROM offline_playlists WHERE playlist_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to disable offline playlist: {}", playlistId, e);
            throw new RuntimeException("Failed to disable offline playlist", e);
        }
    }

    public Optional<OfflinePlaylist> find(UUID serverId, String playlistId) {
        String sql = "SELECT * FROM offline_playlists WHERE playlist_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load offline playlist: {}", playlistId, e);
            throw new RuntimeException("Failed to load offline playlist", e);
        }
        return Optional.empty();
    }

    /** All offline-enabled playlists for a server. */
    public List<OfflinePlaylist> listEnabled(UUID serverId) {
        List<OfflinePlaylist> result = new ArrayList<>();
        String sql = "SELECT * FROM offline_playlists WHERE server_id = ? ORDER BY playlist_id ASC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list offline playlists", e);
            throw new RuntimeException("Failed to list offline playlists", e);
        }
        return result;
    }

    /** Advance the last-synced high-water mark after a successful sync of the playlist. */
    public void updateWatermark(UUID serverId, String playlistId, Instant watermark, Instant now) {
        String sql = "UPDATE offline_playlists SET watermark_ms = ?, updated_at_ms = ? WHERE playlist_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, watermark.toEpochMilli());
            pstmt.setLong(2, now.toEpochMilli());
            pstmt.setString(3, playlistId);
            pstmt.setString(4, serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update offline playlist watermark: {}", playlistId, e);
            throw new RuntimeException("Failed to update offline playlist watermark", e);
        }
    }

    /** Whether the playlist is currently marked offline (a row exists). */
    public boolean isEnabled(UUID serverId, String playlistId) {
        return find(serverId, playlistId).isPresent();
    }

    private OfflinePlaylist mapRow(ResultSet rs) throws SQLException {
        long watermarkMs = rs.getLong("watermark_ms");
        Optional<Instant> watermark = rs.wasNull()
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(watermarkMs));
        return new OfflinePlaylist(
                rs.getString("playlist_id"),
                UUID.fromString(rs.getString("server_id")),
                PlaylistKind.valueOf(rs.getString("kind")),
                watermark,
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms"))
        );
    }
}
