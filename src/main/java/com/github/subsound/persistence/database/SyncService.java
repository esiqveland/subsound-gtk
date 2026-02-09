package com.github.subsound.persistence.database;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.AlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistEntry;
import com.github.subsound.integration.ServerClient.ArtistInfo;
import com.github.subsound.integration.ServerClient.CoverArt;
import com.github.subsound.integration.ServerClient.SongInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class SyncService {
    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    private final ServerClient serverClient;
    private final DatabaseServerService databaseServerService;
    private final UUID serverId;

    public SyncService(ServerClient serverClient, DatabaseServerService databaseServerService, UUID serverId) {
        this.serverClient = serverClient;
        this.databaseServerService = databaseServerService;
        this.serverId = serverId;
    }

    public record SyncStats(int artists, int albums, int songs, int playlists) {}

    public SyncStats syncAll() {
        logger.info("Starting full sync for server: {}", serverId);
        try {
            // Step 1: Fetch artists first to verify server is online before deleting
            var artists = serverClient.getArtists().list();
            logger.info("Fetched {} artists from server", artists.size());

            // Step 2: Truncate existing data (server confirmed online)
            logger.info("Truncating existing data for server: {}", serverId);
            databaseServerService.deleteAllPlaylistSongs();
            databaseServerService.deleteAllPlaylists();
            databaseServerService.deleteAllSongs();
            databaseServerService.deleteAllAlbums();
            databaseServerService.deleteAllArtists();

            // Step 3: Sync all data from server
            var stats = new SyncStats(0, 0, 0, 0);
            for (ArtistEntry artistEntry : artists) {
                var s = syncArtist(artistEntry.id());
                stats = new SyncStats(
                        stats.artists + s.artists,
                        stats.albums + s.albums,
                        stats.songs + s.songs,
                        stats.playlists
                );
            }
            int playlistCount = syncPlaylists();
            stats = new SyncStats(stats.artists, stats.albums, stats.songs, playlistCount);

            // Step 4: Verify and clean up orphaned downloads
            int orphanedDownloads = databaseServerService.removeOrphanedDownloads();
            if (orphanedDownloads > 0) {
                logger.warn("Cleaned up {} orphaned download references", orphanedDownloads);
            }

            logger.info("Synced {} artists, {} albums, {} songs, {} playlists", stats.artists, stats.albums, stats.songs, stats.playlists);
            logger.info("Full sync completed for server: {}", serverId);
            return stats;
        } catch (Exception e) {
            logger.error("Error during full sync", e);
            throw new RuntimeException("Sync failed", e);
        }
    }

    private SyncStats syncArtist(String artistId) {
        ArtistInfo artistInfo = serverClient.getArtistWithAlbums(artistId);
        Artist artist = new Artist(
                artistInfo.id(),
                serverId,
                artistInfo.name(),
                artistInfo.albumCount(),
                artistInfo.starredAt(),
                artistInfo.coverArt().map(ca -> ca.coverArtId()),
                artistInfo.biography() != null ? java.util.Optional.of(new Artist.Biography(artistInfo.biography().original())) : java.util.Optional.empty()
        );
        databaseServerService.insert(artist);

        int songs = 0;
        for (ArtistAlbumInfo albumInfoSimple : artistInfo.albums()) {
            songs += syncAlbum(albumInfoSimple.id(), albumInfoSimple.genre());
        }
        return new SyncStats(1, artistInfo.albums().size(), songs, 0);
    }

    private int syncAlbum(String albumId, java.util.Optional<String> genre) {
        AlbumInfo albumInfo = serverClient.getAlbumInfo(albumId);
        Album album = new Album(
                albumInfo.id(),
                serverId,
                albumInfo.artistId(),
                albumInfo.name(),
                albumInfo.songCount(),
                albumInfo.year(),
                albumInfo.artistName(),
                albumInfo.duration(),
                albumInfo.starredAt(),
                albumInfo.coverArt().map(CoverArt::coverArtId),
                java.time.Instant.now(),
                genre
        );
        databaseServerService.insert(album);

        for (SongInfo songInfo : albumInfo.songs()) {
            Song song = new Song(
                    songInfo.id(),
                    serverId,
                    songInfo.albumId(),
                    songInfo.title(),
                    songInfo.year(),
                    songInfo.artistId(),
                    songInfo.artist(),
                    songInfo.duration(),
                    songInfo.starred(),
                    songInfo.coverArt().map(ca -> ca.coverArtId()),
                    java.time.Instant.now(),
                    songInfo.trackNumber(),
                    songInfo.discNumber(),
                    songInfo.bitRate(),
                    songInfo.size(),
                    songInfo.genre(),
                    songInfo.suffix()
            );
            databaseServerService.insert(song);
        }
        return albumInfo.songs().size();
    }

    private int syncPlaylists() {
        try {
            var playlists = serverClient.getPlaylists().playlists();
            logger.info("Fetched {} playlists", playlists.size());
            for (var playlistSimple : playlists) {
                var playlist = serverClient.getPlaylist(playlistSimple.id());
                PlaylistRow row = new PlaylistRow(
                        playlist.id(),
                        serverId,
                        playlist.name(),
                        playlist.songCount(),
                        playlist.songs().stream()
                                .map(SongInfo::duration)
                                .reduce(java.time.Duration.ZERO, java.time.Duration::plus),
                        playlist.coverArtId().map(CoverArt::coverArtId),
                        playlist.created(),
                        java.time.Instant.now()
                );
                databaseServerService.insert(row);
                // Note: playlist_songs already deleted at start of syncAll()
                for (int i = 0; i < playlist.songs().size(); i++) {
                    databaseServerService.insertPlaylistSong(playlist.id(), playlist.songs().get(i).id(), i);
                }
            }
            return playlists.size();
        } catch (Exception e) {
            logger.error("Error syncing playlists", e);
            return 0;
        }
    }
}
