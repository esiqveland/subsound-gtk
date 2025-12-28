package com.github.subsound.persistence.database;

import com.github.subsound.persistence.database.Artist.Biography;
import com.github.subsound.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseServerService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseServerService.class);
    private final UUID serverId;
    private final Database database;

    public DatabaseServerService(UUID serverId, Database database) {
        this.serverId = serverId;
        this.database = database;
    }

    public void insert(Album album) {
        String sql = """
                INSERT INTO albums (id, server_id, artist_id, name, song_count, year, artist_name, duration_ms, starred_at_ms, cover_art_id, added_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, album.id());
            pstmt.setString(2, album.serverId().toString());
            pstmt.setString(3, album.artistId());
            pstmt.setString(4, album.name());
            pstmt.setInt(5, album.songCount());
            if (album.year().isPresent()) {
                pstmt.setInt(6, album.year().get());
            } else {
                pstmt.setNull(6, Types.INTEGER);
            }
            pstmt.setString(7, album.artistName());
            pstmt.setLong(8, album.duration().toMillis());
            if (album.starredAt().isPresent()) {
                pstmt.setLong(9, album.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(9, Types.INTEGER);
            }
            if (album.coverArtId().isPresent()) {
                pstmt.setString(10, album.coverArtId().get());
            } else {
                pstmt.setNull(10, Types.VARCHAR);
            }
            pstmt.setLong(11, album.addedAt().toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert album", e);
            throw new RuntimeException("Failed to insert album", e);
        }
    }

    public List<Album> listAlbumsByArtist(String artistId) {
        List<Album> albums = new ArrayList<>();
        String sql = "SELECT * FROM albums WHERE server_id = ? AND artist_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, artistId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    albums.add(mapResultSetToAlbum(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list albums for artist: {}", artistId, e);
            throw new RuntimeException("Failed to list albums by artist", e);
        }
        return albums;
    }

    public List<Album> listAlbumsByAddedAt() {
        List<Album> albums = new ArrayList<>();
        String sql = "SELECT * FROM albums WHERE server_id = ? ORDER BY added_at_ms DESC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    albums.add(mapResultSetToAlbum(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list albums by added_at_ms for server: {}", serverId, e);
            throw new RuntimeException("Failed to list albums by added_at_ms", e);
        }
        return albums;
    }

    public Optional<Album> getAlbumById(String albumId) {
        String sql = "SELECT * FROM albums WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, albumId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToAlbum(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get album by id: {}", albumId, e);
            throw new RuntimeException("Failed to get album by id", e);
        }
        return Optional.empty();
    }

    private Album mapResultSetToAlbum(ResultSet rs) throws SQLException {
        int yearValue = rs.getInt("year");
        Optional<Integer> year = rs.wasNull() ? Optional.empty() : Optional.of(yearValue);

        long starredAtMs = rs.getLong("starred_at_ms");
        Optional<Instant> starredAt = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAtMs));

        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        return new Album(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("artist_id"),
                rs.getString("name"),
                rs.getInt("song_count"),
                year,
                rs.getString("artist_name"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                starredAt,
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("added_at_ms"))
        );
    }

    public void insert(Artist artist) {
        String sql = "INSERT INTO artists (id, server_id, name, album_count, starred_at, cover_art_id, biography) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, artist.id());
            pstmt.setString(2, artist.serverId().toString());
            pstmt.setString(3, artist.name());
            pstmt.setInt(4, artist.albumCount());
            if (artist.starredAt().isPresent()) {
                pstmt.setLong(5, artist.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(5, Types.INTEGER);
            }
            if (artist.coverArtId().isPresent()) {
                pstmt.setString(6, artist.coverArtId().get());
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }
            if (artist.biography().isPresent()) {
                var biography = artist.biography().get();
                var data = Utils.toJson(biography);
                pstmt.setBytes(7, data.getBytes(StandardCharsets.UTF_8));
            } else {
                pstmt.setNull(7, Types.BLOB);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert artist", e);
            throw new RuntimeException("Failed to insert artist", e);
        }
    }

    public Optional<Artist> getArtistById(String id) {
        String sql = "SELECT id, server_id, name, album_count, starred_at, cover_art_id, biography FROM artists WHERE server_id = ? AND  id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToArtist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get artist by id: {}", id, e);
            throw new RuntimeException("Failed to get artist by id", e);
        }
        return Optional.empty();
    }

    public List<Artist> listArtists() {
        List<Artist> artists = new ArrayList<>();
        String sql = "SELECT id, server_id, name, album_count, starred_at, cover_art_id, biography FROM artists WHERE server_id = ?";
        try (Connection conn = database.openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    artists.add(mapResultSetToArtist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list artists for server: {}", serverId, e);
            throw new RuntimeException("Failed to list artists", e);
        }
        return artists;
    }

    private Artist mapResultSetToArtist(ResultSet rs) throws SQLException {
        long starredAt = rs.getLong("starred_at");
        Optional<Instant> starredAtInstant = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAt));
        
        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        byte[] biography = rs.getBytes("biography");
        Optional<Biography> biographyOptional = Optional.empty();
        if (biography != null && biography.length > 0) {
            Biography bio = Utils.fromJson(new String(biography, StandardCharsets.UTF_8), Biography.class);
            biographyOptional = Optional.of(bio);
        }

        return new Artist(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("name"),
                rs.getInt("album_count"),
                starredAtInstant,
                coverArtIdOptional,
                biographyOptional
        );
    }
}
