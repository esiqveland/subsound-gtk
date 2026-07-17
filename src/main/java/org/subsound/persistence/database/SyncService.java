package org.subsound.persistence.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.AlbumInfo;
import org.subsound.integration.ServerClient.ArtistAlbumInfo;
import org.subsound.integration.ServerClient.ArtistEntry;
import org.subsound.integration.ServerClient.ArtistInfo;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.integration.ServerClient.SearchPage;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.SongCache;
import org.subsound.persistence.SongCacheChecker;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.persistence.ThumbnailCache.ThumbLoaded;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SyncService {
    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);
    private static final int SEARCH3_PAGE_SIZE = 500;

    private final ServerClient serverClient;
    private final DatabaseServerService databaseServerService;
    private final UUID serverId;
    private final ThumbnailCache thumbnailCache;
    private final SongCacheChecker songCacheChecker;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, CoverArt> collectedCoverArts = new ConcurrentHashMap<>();

    public SyncService(ServerClient serverClient, DatabaseServerService databaseServerService, UUID serverId, ThumbnailCache thumbnailCache, SongCacheChecker songCacheChecker) {
        this.serverClient = serverClient;
        this.databaseServerService = databaseServerService;
        this.serverId = serverId;
        this.thumbnailCache = thumbnailCache;
        this.songCacheChecker = songCacheChecker;
    }

    public record SyncStats(int artists, int albums, int songs, int playlists) {}

    public SyncStats syncAll() {
        var start = System.nanoTime();
        logger.info("Starting full sync for server: {}", serverId);
        try {
            // Step 1: Fetch artists first to verify server is online before deleting
            var artists = serverClient.getArtists().list();
            logger.info("Fetched {} artists from server", artists.size());

            // Probe search3 empty-query support before truncating anything
            boolean useSearch3 = probeSearch3EmptyQuery(artists.size());

            // Step 2: Truncate existing data (server confirmed online)
            logger.info("Truncating existing data for server: {}", serverId);
            databaseServerService.deleteAllPlaylistSongs();
            databaseServerService.deleteAllPlaylists();
            databaseServerService.deleteAllSongs();
            databaseServerService.deleteAllAlbums();
            databaseServerService.deleteAllArtists();

            // Step 3: Sync all data from server
            collectedCoverArts.clear();
            SyncStats stats = useSearch3
                    ? syncViaSearch3()
                    : syncViaArtistWalk(artists);
            int playlistCount = syncPlaylists();
            stats = new SyncStats(stats.artists, stats.albums, stats.songs, playlistCount);

            // Step 4: Verify and clean up orphaned downloads
            int orphanedDownloads = databaseServerService.removeOrphanedDownloads();
            if (orphanedDownloads > 0) {
                logger.warn("Cleaned up {} orphaned download references", orphanedDownloads);
            }

            // Step 5: Verify remaining downloads are still cached on disk, re-queue any missing
            var completedDownloads = databaseServerService.listDownloadQueue(List.of(DownloadQueueItem.DownloadStatus.COMPLETED));
            int requeued = 0;
            for (var item : completedDownloads) {
                var query = new SongCache.SongCacheQuery(item.serverId().toString(), item.songId(), item.streamFormat());
                if (!songCacheChecker.isCached(query)) {
                    databaseServerService.updateDownloadProgress(item.songId(), DownloadQueueItem.DownloadStatus.PENDING, 0.0, null);
                    requeued++;
                }
            }
            if (requeued > 0) {
                logger.info("Re-queued {} downloads with missing cache files", requeued);
            }

            // Step 5b: Clean up CACHED entries whose files are no longer on disk
            var cachedEntries = databaseServerService.listDownloadQueue(List.of(DownloadQueueItem.DownloadStatus.CACHED));
            int removedCached = 0;
            for (var item : cachedEntries) {
                var query = new SongCache.SongCacheQuery(item.serverId().toString(), item.songId(), item.streamFormat());
                if (!songCacheChecker.isCached(query)) {
                    databaseServerService.removeFromDownloadQueue(item.songId());
                    removedCached++;
                }
            }
            if (removedCached > 0) {
                logger.info("Removed {} cached entries with missing cache files", removedCached);
            }

            // Step 6: Cache all collected thumbnails
            logger.info("Caching {} thumbnails", collectedCoverArts.size());
            var thumbFutures = collectedCoverArts.values().stream()
                    .map(ca -> thumbnailCache.loadThumbAsync(ca).handle((loaded, throwable) -> {
                        if (throwable != null) {
                            logger.warn("Failed to cache thumbnail for coverArtId={}", ca.coverArtId(), throwable);
                            return Optional.<ThumbLoaded>empty();
                        } else {
                            return Optional.of(loaded);
                        }
                    }))
                    .toList();
            var thumbResults = thumbFutures.stream().map(CompletableFuture::join).toList();
            long thumbFailures = thumbResults.stream().filter(Optional::isEmpty).count();
            long thumbSuccess = thumbResults.stream().filter(Optional::isPresent).count();;
            logger.info("Finished caching thumbnails success={} failures={}", thumbSuccess, thumbFailures);

            var elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            // warn level to make sure we always print this summary
            logger.warn("Synced {} artists, {} albums, {} songs, {} playlists, thumbSuccess={} thumbFailures={} in {}ms", stats.artists, stats.albums, stats.songs, stats.playlists, thumbSuccess, thumbFailures, elapsedMillis);
            logger.info("Full sync completed for server: {}", serverId);
            return stats;
        } catch (Exception e) {
            logger.error("Error during full sync", e);
            throw new RuntimeException("Sync failed", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * OpenSubsonic servers must support search3 with an empty query returning the whole
     * library. Probe with a single-song page so we can fall back to the per-artist walk
     * on servers that reject the empty query (Subsonic error 10) or silently return nothing.
     */
    private boolean probeSearch3EmptyQuery(int artistCount) {
        try {
            var probe = serverClient.search3("", SearchPage.songs(1, 0));
            if (probe.songs().isEmpty() && artistCount > 0) {
                logger.info("search3 empty-query probe returned no songs; using artist walk");
                return false;
            }
            logger.info("search3 empty-query supported; using paged search3 sync");
            return true;
        } catch (Exception e) {
            logger.info("search3 empty-query not supported ({}); using artist walk", e.getMessage());
            return false;
        }
    }

    private SyncStats syncViaArtistWalk(List<ArtistEntry> artists) throws Exception {
        List<Future<SyncStats>> futures = new ArrayList<>();
        for (ArtistEntry artistEntry : artists) {
            futures.add(executor.submit(() -> syncArtist(artistEntry.id())));
        }
        // Wait for all and aggregate stats
        var stats = new SyncStats(0, 0, 0, 0);
        for (Future<SyncStats> future : futures) {
            var s = future.get();
            stats = new SyncStats(
                    stats.artists + s.artists,
                    stats.albums + s.albums,
                    stats.songs + s.songs,
                    stats.playlists
            );
        }
        return stats;
    }

    private SyncStats syncViaSearch3() throws Exception {
        var artistsTask = executor.submit(this::syncArtistsViaSearch3);
        var albumsTask = executor.submit(this::fetchAlbumsViaSearch3);
        var songsByAlbum = fetchSongsViaSearch3();
        int artistCount = artistsTask.get();
        Map<String, Album> albums = albumsTask.get();

        int songCount = 0;
        for (Album album : albums.values()) {
            List<DBSong> songs = songsByAlbum.remove(album.id());
            if (songs == null) {
                songs = List.of();
            }
            databaseServerService.syncAlbumBatch(album, songs);
            songCount += songs.size();
        }
        if (!songsByAlbum.isEmpty()) {
            int orphans = songsByAlbum.values().stream().mapToInt(List::size).sum();
            logger.warn("search3 sync: skipped {} songs across {} albumIds missing from album list", orphans, songsByAlbum.size());
        }
        return new SyncStats(artistCount, albums.size(), songCount, 0);
    }

    private int syncArtistsViaSearch3() {
        int offset = 0;
        int count = 0;
        int skipped = 0;
        while (true) {
            var page = serverClient.search3("", SearchPage.artists(SEARCH3_PAGE_SIZE, offset)).artists();
            for (ArtistEntry entry : page) {
                // search3 returns every artist incl. participant-only ones (composers,
                // performers); getArtists only lists album artists. Skip album-less
                // artists so the offline artists list matches the online one.
                if (entry.albumCount() <= 0) {
                    skipped++;
                    continue;
                }
                Artist artist = new Artist(
                        entry.id(),
                        serverId,
                        entry.name(),
                        entry.albumCount(),
                        entry.starredAt(),
                        entry.coverArt().map(CoverArt::coverArtId),
                        Optional.empty()
                );
                databaseServerService.insert(artist);
                entry.coverArt().ifPresent(ca -> collectedCoverArts.put(ca.coverArtId(), ca));
                count++;
            }
            if (page.size() < SEARCH3_PAGE_SIZE) {
                if (skipped > 0) {
                    logger.info("search3 sync: skipped {} artists without albums", skipped);
                }
                return count;
            }
            offset += SEARCH3_PAGE_SIZE;
        }
    }

    private Map<String, Album> fetchAlbumsViaSearch3() {
        var addedAt = java.time.Instant.now();
        var albums = new LinkedHashMap<String, Album>();
        int offset = 0;
        while (true) {
            var page = serverClient.search3("", SearchPage.albums(SEARCH3_PAGE_SIZE, offset)).albums();
            for (ArtistAlbumInfo info : page) {
                albums.put(info.id(), new Album(
                        info.id(),
                        serverId,
                        info.artistId(),
                        info.name(),
                        info.songCount(),
                        info.year(),
                        info.artistName(),
                        info.duration(),
                        info.starredAt(),
                        info.coverArt().map(CoverArt::coverArtId),
                        addedAt,
                        info.genre()
                ));
                info.coverArt().ifPresent(ca -> collectedCoverArts.put(ca.coverArtId(), ca));
            }
            if (page.size() < SEARCH3_PAGE_SIZE) {
                return albums;
            }
            offset += SEARCH3_PAGE_SIZE;
        }
    }

    private Map<String, List<DBSong>> fetchSongsViaSearch3() {
        var songsByAlbum = new HashMap<String, List<DBSong>>();
        int offset = 0;
        while (true) {
            var page = serverClient.search3("", SearchPage.songs(SEARCH3_PAGE_SIZE, offset)).songs();
            for (SongInfo songInfo : page) {
                songsByAlbum
                        .computeIfAbsent(songInfo.albumId(), _ -> new ArrayList<>())
                        .add(DBSong.from(songInfo, serverId));
                songInfo.coverArt().ifPresent(ca -> collectedCoverArts.put(ca.coverArtId(), ca));
            }
            if (page.size() < SEARCH3_PAGE_SIZE) {
                return songsByAlbum;
            }
            offset += SEARCH3_PAGE_SIZE;
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

        // Collect artist cover art for thumbnail caching
        artistInfo.coverArt().ifPresent(coverArt -> collectedCoverArts.put(coverArt.coverArtId(), coverArt));

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
        // Collect album cover art for thumbnail caching
        albumInfo.coverArt().ifPresent(coverArt -> collectedCoverArts.put(coverArt.coverArtId(), coverArt));

        var start = System.nanoTime();

        List<DBSong> songs = new ArrayList<>();
        for (SongInfo songInfo : albumInfo.songs()) {
            songs.add(DBSong.from(songInfo, serverId));

            // Collect song cover art for thumbnail caching
            songInfo.coverArt().ifPresent(coverArt -> collectedCoverArts.put(coverArt.coverArtId(), coverArt));
        }
        databaseServerService.syncAlbumBatch(album, songs);
        var elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        logger.info("syncing: songs={} for album {} in {}ms", albumInfo.songs().size(), albumInfo.id(), elapsedMillis);
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
                databaseServerService.upsertPlaylist(row);
                // Note: playlist_songs already deleted at start of syncAll()
                databaseServerService.insertPlaylistSongs(
                        playlist.id(),
                        playlist.songs().stream().map(SongInfo::id).toList()
                );
            }
            return playlists.size();
        } catch (Exception e) {
            logger.error("Error syncing playlists", e);
            return 0;
        }
    }
}
