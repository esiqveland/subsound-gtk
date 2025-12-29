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

    record SyncStats(int artists, int albums, int songs) {}
    public SyncStats syncAll() {
        logger.info("Starting full sync for server: {}", serverId);
        try {
            var artists = serverClient.getArtists().list();
            logger.info("Fetched {} artists", artists.size());
            var stats = new SyncStats(0, 0, 0);
            for (ArtistEntry artistEntry : artists) {
                var s = syncArtist(artistEntry.id());
                stats = new SyncStats(
                        stats.artists + s.artists,
                        stats.albums + s.albums,
                        stats.songs + s.songs
                );
            }
            logger.info("Synced {} artists, {} albums, {} songs", stats.artists, stats.albums, stats.songs);
            logger.info("Full sync completed for server: {}", serverId);
            return stats;
        } catch (Exception e) {
            logger.error("Error during full sync", e);
            throw new RuntimeException("Sync failed", e);
        }
    }

    private SyncStats syncArtist(String artistId) {
        ArtistInfo artistInfo = serverClient.getArtistInfo(artistId);
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
            songs += syncAlbum(albumInfoSimple.id());
        }
        return new SyncStats(1, artistInfo.albums().size(), songs);
    }

    private int syncAlbum(String albumId) {
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
                java.time.Instant.now()
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
                    java.time.Instant.now()
            );
            databaseServerService.insert(song);
        }
        return albumInfo.songs().size();
    }
}
