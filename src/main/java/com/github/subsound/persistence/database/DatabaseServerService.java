package com.github.subsound.persistence.database;

import com.github.subsound.persistence.database.Artist.Biography;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
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
                INSERT OR REPLACE INTO albums (id, server_id, artist_id, name, song_count, year, artist_name, duration_ms, starred_at_ms, cover_art_id, added_at_ms, genre)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            if (album.genre().isPresent()) {
                pstmt.setString(12, album.genre().get());
            } else {
                pstmt.setNull(12, Types.VARCHAR);
            }
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

        String genre = rs.getString("genre");
        Optional<String> genreOptional = Optional.ofNullable(genre);

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
                Instant.ofEpochMilli(rs.getLong("added_at_ms")),
                genreOptional
        );
    }

    public void insert(Artist artist) {
        String sql = "INSERT OR REPLACE INTO artists (id, server_id, name, album_count, starred_at, cover_art_id, biography) VALUES (?, ?, ?, ?, ?, ?, ?)";
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

    public void insert(Song song) {
        String sql = """
                INSERT OR REPLACE INTO songs (id, server_id, album_id, name, year, artist_id, artist_name, duration_ms, starred_at_ms, cover_art_id, created_at_ms, track_number, disc_number, bit_rate, size, genre, suffix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, song.id());
            pstmt.setString(2, song.serverId().toString());
            pstmt.setString(3, song.albumId());
            pstmt.setString(4, song.name());
            if (song.year().isPresent()) {
                pstmt.setInt(5, song.year().get());
            } else {
                pstmt.setNull(5, Types.INTEGER);
            }
            pstmt.setString(6, song.artistId());
            pstmt.setString(7, song.artistName());
            pstmt.setLong(8, song.duration().toMillis());
            if (song.starredAt().isPresent()) {
                pstmt.setLong(9, song.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(9, Types.INTEGER);
            }
            if (song.coverArtId().isPresent()) {
                pstmt.setString(10, song.coverArtId().get());
            } else {
                pstmt.setNull(10, Types.VARCHAR);
            }
            pstmt.setLong(11, song.createdAt().toEpochMilli());
            if (song.trackNumber().isPresent()) {
                pstmt.setInt(12, song.trackNumber().get());
            } else {
                pstmt.setNull(12, Types.INTEGER);
            }
            if (song.discNumber().isPresent()) {
                pstmt.setInt(13, song.discNumber().get());
            } else {
                pstmt.setNull(13, Types.INTEGER);
            }
            if (song.bitRate().isPresent()) {
                pstmt.setInt(14, song.bitRate().get());
            } else {
                pstmt.setNull(14, Types.INTEGER);
            }
            pstmt.setLong(15, song.size());
            pstmt.setString(16, song.genre());
            pstmt.setString(17, song.suffix());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert song", e);
            throw new RuntimeException("Failed to insert song", e);
        }
    }

    public List<Song> listSongsByAlbumId(String albumId) {
        List<Song> songs = new ArrayList<>();
        String sql = "SELECT * FROM songs WHERE server_id = ? AND album_id = ? ORDER BY disc_number, track_number";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, albumId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list songs for album: {}", albumId, e);
            throw new RuntimeException("Failed to list songs by album_id", e);
        }
        return songs;
    }

    public List<Song> listSongsByStarredAt() {
        List<Song> songs = new ArrayList<>();
        String sql = "SELECT * FROM songs WHERE server_id = ? AND starred_at_ms IS NOT NULL ORDER BY starred_at_ms DESC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list starred songs for server: {}", serverId, e);
            throw new RuntimeException("Failed to list starred songs", e);
        }
        return songs;
    }

    public Optional<Song> getSongById(String songId) {
        String sql = "SELECT * FROM songs WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, songId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get song by id: {}", songId, e);
            throw new RuntimeException("Failed to get song by id", e);
        }
        return Optional.empty();
    }

    private Song mapResultSetToSong(ResultSet rs) throws SQLException {
        int year = rs.getInt("year");
        Optional<Integer> yearOptional = rs.wasNull() ? Optional.empty() : Optional.of(year);

        long starredAt = rs.getLong("starred_at_ms");
        Optional<Instant> starredAtInstant = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAt));

        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        int trackNumber = rs.getInt("track_number");
        Optional<Integer> trackNumberOpt = rs.wasNull() ? Optional.empty() : Optional.of(trackNumber);

        int discNumber = rs.getInt("disc_number");
        Optional<Integer> discNumberOpt = rs.wasNull() ? Optional.empty() : Optional.of(discNumber);

        int bitRate = rs.getInt("bit_rate");
        Optional<Integer> bitRateOpt = rs.wasNull() ? Optional.empty() : Optional.of(bitRate);

        return new Song(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("album_id"),
                rs.getString("name"),
                yearOptional,
                rs.getString("artist_id"),
                rs.getString("artist_name"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                starredAtInstant,
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                trackNumberOpt,
                discNumberOpt,
                bitRateOpt,
                rs.getLong("size"),
                rs.getString("genre") != null ? rs.getString("genre") : "",
                rs.getString("suffix") != null ? rs.getString("suffix") : ""
        );
    }

    // Playlist methods

    public void insert(PlaylistRow playlist) {
        String sql = """
                INSERT OR REPLACE INTO playlists (id, server_id, name, song_count, duration_ms, cover_art_id, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlist.id());
            pstmt.setString(2, playlist.serverId().toString());
            pstmt.setString(3, playlist.name());
            pstmt.setInt(4, playlist.songCount());
            pstmt.setLong(5, playlist.duration().toMillis());
            if (playlist.coverArtId().isPresent()) {
                pstmt.setString(6, playlist.coverArtId().get());
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }
            pstmt.setLong(7, playlist.createdAt().toEpochMilli());
            pstmt.setLong(8, playlist.updatedAt().toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert playlist", e);
            throw new RuntimeException("Failed to insert playlist", e);
        }
    }

    public void insertPlaylistSong(String playlistId, String songId, int sortOrder) {
        String sql = "INSERT OR REPLACE INTO playlist_songs (playlist_id, server_id, song_id, sort_order) VALUES (?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.setString(3, songId);
            pstmt.setInt(4, sortOrder);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert playlist song", e);
            throw new RuntimeException("Failed to insert playlist song", e);
        }
    }

    public void deletePlaylistSongs(String playlistId) {
        String sql = "DELETE FROM playlist_songs WHERE playlist_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete playlist songs", e);
            throw new RuntimeException("Failed to delete playlist songs", e);
        }
    }

    public List<PlaylistRow> listPlaylists() {
        List<PlaylistRow> playlists = new ArrayList<>();
        String sql = "SELECT * FROM playlists WHERE server_id = ? ORDER BY name";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    playlists.add(mapResultSetToPlaylist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list playlists for server: {}", serverId, e);
            throw new RuntimeException("Failed to list playlists", e);
        }
        return playlists;
    }

    public Optional<PlaylistRow> getPlaylistById(String playlistId) {
        String sql = "SELECT * FROM playlists WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, playlistId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPlaylist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get playlist by id: {}", playlistId, e);
            throw new RuntimeException("Failed to get playlist by id", e);
        }
        return Optional.empty();
    }

    public List<String> listPlaylistSongIds(String playlistId) {
        List<String> songIds = new ArrayList<>();
        String sql = "SELECT song_id FROM playlist_songs WHERE playlist_id = ? AND server_id = ? ORDER BY sort_order";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songIds.add(rs.getString("song_id"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list playlist songs for playlist: {}", playlistId, e);
            throw new RuntimeException("Failed to list playlist songs", e);
        }
        return songIds;
    }

    private PlaylistRow mapResultSetToPlaylist(ResultSet rs) throws SQLException {
        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        return new PlaylistRow(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("name"),
                rs.getInt("song_count"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms"))
        );
    }

    public void addToDownloadQueue(SongInfo songInfo) {
        String sql = "INSERT OR IGNORE INTO download_queue (song_id, server_id, status, stream_uri, stream_format, original_size, original_bitrate, estimated_bitrate, duration_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, songInfo.id());
            pstmt.setString(2, this.serverId.toString());
            pstmt.setString(3, DownloadQueueItem.DownloadStatus.PENDING.name());
            pstmt.setString(4, songInfo.transcodeInfo().streamUri().toString());
            pstmt.setString(5, songInfo.transcodeInfo().streamFormat());
            pstmt.setLong(6, songInfo.size());
            if (songInfo.transcodeInfo().originalBitRate().isPresent()) {
                pstmt.setInt(7, songInfo.transcodeInfo().originalBitRate().get());
            } else {
                pstmt.setNull(7, Types.INTEGER);
            }
            pstmt.setInt(8, songInfo.transcodeInfo().estimatedBitRate());
            pstmt.setLong(9, songInfo.transcodeInfo().duration().toSeconds());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add song to download queue: {}", songInfo.id(), e);
            throw new RuntimeException("Failed to add song to download queue", e);
        }
    }

    public List<DownloadQueueItem> listDownloadQueue() {
        return listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
    }
    public List<DownloadQueueItem> listDownloadQueue(List<DownloadStatus> statuses) {
        List<DownloadQueueItem> items = new ArrayList<>();
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = "SELECT * FROM download_queue WHERE server_id = ? AND status IN (" + placeholders + ")";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            for (int i = 0; i < statuses.size(); i++) {
                pstmt.setString(i + 2, statuses.get(i).name());
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int originalBitRate = rs.getInt("original_bitrate");
                    Optional<Integer> originalBitRateOpt = rs.wasNull() ? Optional.empty() : Optional.of(originalBitRate);

                    items.add(new DownloadQueueItem(
                            rs.getString("song_id"),
                            UUID.fromString(rs.getString("server_id")),
                            DownloadQueueItem.DownloadStatus.valueOf(rs.getString("status")),
                            rs.getDouble("progress"),
                            rs.getString("error_message"),
                            rs.getString("stream_uri"),
                            rs.getString("stream_format"),
                            rs.getLong("original_size"),
                            originalBitRateOpt,
                            rs.getInt("estimated_bitrate"),
                            rs.getLong("duration_seconds"),
                            Optional.ofNullable(rs.getString("checksum"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list download queue for server: {}", serverId, e);
            throw new RuntimeException("Failed to list download queue", e);
        }
        return items;
    }

    public void updateDownloadProgress(String songId, DownloadQueueItem.DownloadStatus status, double progress, String errorMessage) {
        updateDownloadProgress(songId, status, progress, errorMessage, null);
    }

    public void updateDownloadProgress(String songId, DownloadQueueItem.DownloadStatus status, double progress, String errorMessage, String checksum) {
        String sql = "UPDATE download_queue SET status = ?, progress = ?, error_message = ?, checksum = ? WHERE song_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.name());
            pstmt.setDouble(2, progress);
            pstmt.setString(3, errorMessage);
            pstmt.setString(4, checksum);
            pstmt.setString(5, songId);
            pstmt.setString(6, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update download progress for song: {}", songId, e);
            throw new RuntimeException("Failed to update download progress", e);
        }
    }

    public void removeFromDownloadQueue(String songId) {
        String sql = "DELETE FROM download_queue WHERE song_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, songId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove song from download queue: {}", songId, e);
            throw new RuntimeException("Failed to remove song from download queue", e);
        }
    }
}
