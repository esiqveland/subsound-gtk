package org.subsound.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DownloadQueueItem;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.subsound.persistence.database.DownloadSource;
import org.subsound.utils.Utils;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class DownloadManager implements DownloadNotifier {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final DatabaseServerService dbService;
    private final SongCache songCache;
    private final Supplier<ServerClient> clientSupplier;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r);
        t.setDaemon(true);
        t.setName("download-manager");
        return t;
    });

    private final List<Consumer<DownloadManagerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, DownloadQueueItem> downloadQueue = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public DownloadManager(
            DatabaseServerService dbService,
            SongCache songCache,
            Supplier<ServerClient> clientSupplier
    ) {
        this.dbService = dbService;
        this.songCache = songCache;
        this.clientSupplier = clientSupplier;
        // Warm both caches from DB in a single query: queuedIds tracks non-CACHED,
        // songStatusCache holds the full item so lookups don't round-trip to DB.
        dbService.listDownloadQueue(List.of(DownloadStatus.values())).forEach(item -> {
            downloadQueue.put(item.songId(), item);
        });
        startQueueProcessor();
    }

    public void subscribe(Consumer<DownloadManagerEvent> listener) {
        listeners.add(listener);
    }

    public List<DownloadQueueItem> listDownloadQueue() {
        return dbService.listDownloadQueue();
    }

    public List<org.subsound.persistence.database.DBSong> listDownloadedDBSongs() {
        return dbService.listDownloadedSongs();
    }

    public void resetSongCache() {
        // Snapshot every song we're currently tracking — each will need a UI
        // refresh once we've reset the DB state below.
        var affectedSongIds = new HashSet<>(this.downloadQueue.keySet());

        // clear all our DownloadStatus.CACHED items from the table:
        dbService.clearDownloadsWithStatus(DownloadStatus.CACHED);
        // keep the rest and set them to pending:
        dbService.resetDownloadStatusTo(DownloadStatus.PENDING);

        // Rehydrate in-memory maps from the DB. CACHED rows are gone; the rest
        // are PENDING now. Mirrors the warm-up loop in the constructor.
        this.downloadQueue.clear();
        dbService.listDownloadQueue(List.of(DownloadStatus.values())).forEach(item -> {
            downloadQueue.put(item.songId(), item);
        });

        // publishEvent emits REMOVED_FROM_QUEUE when the song is no longer
        // tracked (previously CACHED) and DOWNLOAD_PENDING when it was kept.
        for (var songId : affectedSongIds) {
            this.publishEvent(songId);
        }
    }

    public record DownloadManagerEvent(
            String songId,
            Type type,
            Optional<DownloadQueueItem> item
    ) {
        public enum Type {
            REMOVED_FROM_QUEUE,
            DOWNLOAD_PENDING,
            DOWNLOAD_STARTED,
            DOWNLOAD_COMPLETED,
            DOWNLOAD_FAILED,
            SONG_CACHED;
        }
    }

    private void startQueueProcessor() {
        executor.scheduleAtFixedRate(() -> {
            if (!this.isRunning()) {
                return;
            }
            try {
                processQueue();
            } catch (Exception e) {
                log.error("Error in download queue processor", e);
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS);
    }

    public Optional<DownloadQueueItem> getSongStatus(String songId) {
        return Optional.ofNullable(downloadQueue.get(songId));
    }
    // loadSong loads fresh data from db
    private Optional<DownloadQueueItem> getDownloadItemFromDb(String songId) {
        return dbService.getDownloadQueueItem(songId);
    }

    public void enqueue(SongInfo songInfo) {
        enqueue(songInfo, DownloadSource.ADDED_BY_USER);
    }

    public void enqueue(SongInfo songInfo, DownloadSource source) {
        var current = getSongStatus(songInfo.id());
        if (current.isPresent() && current.get().status() == DownloadStatus.COMPLETED) {
            // Already downloaded. A manual (ADDED_BY_USER) enqueue still escalates the sticky
            // source so a previously playlist-synced song isn't later demoted; a PLAYLIST_SYNC
            // enqueue is a no-op (never downgrades).
            if (source == DownloadSource.ADDED_BY_USER) {
                dbService.escalateSourceToUser(songInfo.id());
                this.getDownloadItemFromDb(songInfo.id()).ifPresent(s -> downloadQueue.put(songInfo.id(), s));
            }
            return;
        }
        dbService.addToDownloadQueue(songInfo, source);
        // update cached data:
        this.getDownloadItemFromDb(songInfo.id()).ifPresent(s -> downloadQueue.put(songInfo.id(), s));
        this.publishEvent(songInfo.id());
    }

    public record DownloadCounts(int completed, int total) {
        public boolean allDone() { return total > 0 && completed == total; }
        public boolean isEmpty() { return total == 0; }
    }

    public DownloadCounts getDownloadCounts() {
        int total = 0;
        int completed = 0;
        for (var item : downloadQueue.values()) {
            if (item.status() == DownloadStatus.CACHED) {
                continue;
            }
            total++;
            if (item.status() == DownloadStatus.COMPLETED) {
                completed++;
            }
        }
        return new DownloadCounts(completed, total);
    }

    public void removeFromQueue(String songId) {
        var current = getSongStatus(songId);
        if (current.isPresent() && current.get().status().isDownloaded()) {
            // File is on disk. keep it available offline but remove from explicit download list:
            dbService.updateDownloadProgress(songId, DownloadStatus.CACHED, 1.0, null);
            // update cached data:
            this.getDownloadItemFromDb(songId).ifPresent(s -> downloadQueue.put(s.songId(), s));
        } else {
            // Not yet downloaded (PENDING / DOWNLOADING / FAILED) — remove entirely
            dbService.removeFromDownloadQueue(songId);
            downloadQueue.remove(songId);
        }
        this.publishEvent(songId);
    }

    /**
     * Resolve the TranscodeInfo to use when fetching this song from cache or server.
     * If a DownloadQueueItem already exists for the song, its stored streamFormat wins —
     * that's the format whatever file is on disk was downloaded with, so the cache key
     * must match it. For songs that have never been queued, ask the ServerClient
     * for its current transcode policy rather than trusting the (potentially stale)
     * format embedded in songInfo.transcodeInfo().
     *
     * For PENDING/FAILED rows the stored format is suspect (transcode policy may have
     * changed since enqueue), so we refresh from the live client and persist before
     * returning. DOWNLOADING/COMPLETED/CACHED rows are left alone.
     */
    private TranscodeInfo effectiveTranscodeInfo(SongInfo songInfo) {
        var existing = downloadQueue.get(songInfo.id());
        if (existing == null) {
            var client = clientSupplier.get();
            return client.currentTranscodeInfo(songInfo);
        }
        if (existing.status() == DownloadStatus.PENDING || existing.status() == DownloadStatus.FAILED) {
            return currentOrStoredTranscodeInfo(existing);
        }
        return new TranscodeInfo(
                songInfo.id(),
                existing.originalBitRate(),
                existing.estimatedBitRate(),
                Duration.ofSeconds(existing.durationSeconds()),
                existing.streamFormat()
        );
    }

    /**
     * Resolve the TranscodeInfo to use for a PENDING/FAILED row, refreshing
     * the row's stored format when the live transcode policy has changed.
     * The in-memory downloadQueue map is intentionally NOT updated here — the
     * downstream state transition (DOWNLOADING → COMPLETED) reloads it from DB
     * naturally. Falls back to the row's stored TranscodeInfo when the live
     * client or DBSong is unavailable.
     */
    private TranscodeInfo currentOrStoredTranscodeInfo(DownloadQueueItem item) {
        var stored = new TranscodeInfo(
                item.songId(),
                item.originalBitRate(),
                item.estimatedBitRate(),
                Duration.ofSeconds(item.durationSeconds()),
                item.streamFormat()
        );
        var client = clientSupplier.get();
        if (client == null) {
            return stored;
        }
        var dbSong = dbService.getSongById(item.songId());
        if (dbSong.isEmpty()) {
            return stored;
        }
        var song = dbSong.get();
        var fresh = client.currentTranscodeInfo(item.songId(), song.bitRate(), song.duration(), song.suffix());
        if (fresh.streamFormat().equals(stored.streamFormat())
                && fresh.estimatedBitRate() == stored.estimatedBitRate()) {
            return stored;
        }
        log.info("Refreshing stored transcode format for song={} {}/{} -> {}/{}",
                item.songId(), stored.streamFormat(), stored.estimatedBitRate(),
                fresh.streamFormat(), fresh.estimatedBitRate());
        dbService.updateDownloadTranscodeInfo(item.songId(), fresh);
        return fresh;
    }

    /**
     * Returns true if a usable copy of this song exists on disk for offline playback.
     * Trusts the download_queue: a song is offline-available iff there is a row whose
     * status is downloaded (COMPLETED/CACHED) and whose persisted streamFormat
     * matches a file on disk. Never consults the live server — this method must
     * work when the network is gone.
     */
    public boolean isAvailableOffline(SongInfo songInfo) {
        var existing = downloadQueue.get(songInfo.id());
        if (existing == null || !existing.status().isDownloaded()) {
            return false;
        }
        var query = new SongCache.SongCacheQuery(
                existing.serverId().toString(),
                songInfo.id(),
                existing.streamFormat()
        );
        return songCache.isCached(query);
    }

    /**
     * Fetch a song for immediate playback. Returns the local URI of the cached
     * file (HIT) or downloads it first (MISS). Either way, the resolved streamFormat
     * is the one persisted in download_queue when available, ensuring the cache
     * key always matches what's on disk.
     *
     * On a MISS, the row is upserted (CACHED for ad-hoc plays, COMPLETED for
     * explicit downloads) and a checksum is computed.
     */
    public SongCache.LoadSongResult loadForPlayback(
            SongInfo songInfo,
            SongCache.DownloadProgressHandler progressHandler
    ) {
        // Sanity check before resolving format: a row marked as downloaded should
        // have its file on disk. If not, the cache is corrupted; demote to PENDING
        // and let the rest of the flow re-download it (with a fresh checksum).
        var existingDownloaded = downloadQueue.get(songInfo.id());
        if (existingDownloaded != null && existingDownloaded.status().isDownloaded()) {
            var query = new SongCache.SongCacheQuery(
                    existingDownloaded.serverId().toString(),
                    songInfo.id(),
                    existingDownloaded.streamFormat()
            );
            if (!songCache.isCached(query)) {
                log.warn("Song marked as {} but cache file is missing — re-downloading: {}",
                        existingDownloaded.status(), songInfo.id());
                dbService.updateDownloadProgress(songInfo.id(), DownloadStatus.PENDING, 0.0, null);
                this.getDownloadItemFromDb(songInfo.id()).ifPresent(s -> downloadQueue.put(s.songId(), s));
                this.publishEvent(songInfo.id());
            }
        }

        var transcodeInfo = effectiveTranscodeInfo(songInfo);
        var serverIdStr = dbService.getServerId().toString();
        var cacheSong = new SongCache.CacheSong(
                serverIdStr,
                songInfo.id(),
                transcodeInfo,
                songInfo.suffix(),
                songInfo.size(),
                progressHandler
        );
        var result = songCache.getSong(cacheSong);
        if (result.result() == SongCache.CacheResult.CANCELLED) {
            return result;
        }

        // Skip the hash + DB write when the row already records this file as downloaded.
        // The hash is a full-file read; doing it on every HIT (e.g. on each prefetch)
        // is pure overhead.
        var existing = downloadQueue.get(songInfo.id());
        if (existing != null && existing.status().isDownloaded()) {
            return result;
        }

        String checksum = null;
        try (var is = result.uri().toURL().openStream()) {
            checksum = Utils.sha256(is);
        } catch (Exception e) {
            log.warn("Failed to calculate checksum for song: {}", songInfo.id(), e);
        }

        if (existing != null) {
            dbService.updateDownloadProgress(songInfo.id(), DownloadStatus.COMPLETED, 1.0, null, checksum);
            this.getDownloadItemFromDb(songInfo.id()).ifPresent(s -> downloadQueue.put(s.songId(), s));
            this.publishEvent(songInfo.id());
        } else {
            // Persist with the transcodeInfo we actually used — markAsCached reads
            // streamFormat/bitrate off the SongInfo, which may be stale.
            this.markAsCached(songInfo.withTranscodeInfo(transcodeInfo), checksum);
        }
        return result;
    }

    public void markAsCached(SongInfo songInfo, String checksum) {
        var current = getSongStatus(songInfo.id());
        if (current.isPresent() && current.get().status().isDownloaded()) {
            return;
        }
        dbService.addToCacheTracking(songInfo, checksum);
        // update cached data:
        this.getDownloadItemFromDb(songInfo.id()).ifPresent(s -> downloadQueue.put(s.songId(), s));
        this.publishEvent(songInfo.id());
    }

    private void publishEvent(String songId) {
        this.publishEvent(songId, this.getSongStatus(songId));
    }

    private void publishEvent(String songId, Optional<DownloadQueueItem> next) {
        var event = next
                .map(item -> new DownloadManagerEvent(
                        songId,
                        switch (item.status()) {
                            case PENDING -> DownloadManagerEvent.Type.DOWNLOAD_PENDING;
                            case DOWNLOADING -> DownloadManagerEvent.Type.DOWNLOAD_STARTED;
                            case COMPLETED -> DownloadManagerEvent.Type.DOWNLOAD_COMPLETED;
                            case FAILED -> DownloadManagerEvent.Type.DOWNLOAD_FAILED;
                            case CACHED -> DownloadManagerEvent.Type.SONG_CACHED;
                        },
                        next
                )).orElseGet(() -> new DownloadManagerEvent(songId, DownloadManagerEvent.Type.REMOVED_FROM_QUEUE, next));
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Download listener threw for song {}", songId, e);
            }
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> cls) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cls.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private void processQueue() {
        List<DownloadQueueItem> pendingItems = dbService.listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
        for (DownloadQueueItem item : pendingItems) {
            if (!this.running) {
                return;
            }
            if (item.status() == DownloadStatus.PENDING || item.status() == DownloadStatus.DOWNLOADING) {
                downloadSong(item);
            }
        }
    }

    private void downloadSong(DownloadQueueItem item) {
        try {
            // Refresh format from current transcode policy before downloading.
            // After resetSongCache rows go back to PENDING with their old format —
            // refreshing here ensures re-downloads pick up the latest user setting.
            // DOWNLOADING rows (resumed mid-flight) are left alone.
            final TranscodeInfo transcodeInfo;
            if (item.status() == DownloadStatus.PENDING || item.status() == DownloadStatus.FAILED) {
                transcodeInfo = currentOrStoredTranscodeInfo(item);
            } else {
                transcodeInfo = new TranscodeInfo(
                        item.songId(),
                        item.originalBitRate(),
                        item.estimatedBitRate(),
                        Duration.ofSeconds(item.durationSeconds()),
                        item.streamFormat()
                );
            }

            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, item.progress(), null);
            downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.DOWNLOADING).withProgress(0));
            this.publishEvent(item.songId());

            var cacheSong = new SongCache.CacheSong(
                    item.serverId().toString(),
                    item.songId(),
                    transcodeInfo,
                    "", // originalFileSuffix - maybe not critical if we have transcodeInfo
                    item.originalSize(),
                    (total, count) -> {
                        double progress = (double) count / total;
                        downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.DOWNLOADING).withProgress(progress));
                    }
            );

            var result = songCache.getSong(cacheSong);

            String checksum = null;
            if (result.result() == SongCache.CacheResult.HIT || result.result() == SongCache.CacheResult.MISS) {
                try (var is = result.uri().toURL().openStream()) {
                    checksum = Utils.sha256(is);
                } catch (Exception e) {
                    log.warn("Failed to calculate checksum for downloaded song: {}", item.songId(), e);
                }
            }

            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.COMPLETED, 1.0, null, checksum);
            this.getDownloadItemFromDb(item.songId()).ifPresent(s -> downloadQueue.put(s.songId(), s));
            this.publishEvent(item.songId());
            log.info("Downloaded song: {} with checksum: {}", item.songId(), checksum);
        } catch (Exception e) {
            if (!this.running && hasCause(e, InterruptedIOException.class)) {
                log.info("Download cancelled during shutdown: {}", item.songId());
                return;
            }
            log.error("Failed to download song: {}", item.songId(), e);
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.FAILED, 0.0, e.getMessage());
            downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.FAILED).withProgress(0.0).withErrorMessage(e.getMessage()));
            this.publishEvent(item.songId());
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }
}
