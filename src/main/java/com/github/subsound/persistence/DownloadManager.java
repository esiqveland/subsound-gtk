package com.github.subsound.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.DownloadQueueItem;
import com.github.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DownloadManager {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final DatabaseServerService dbService;
    private final SongCache songCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Consumer<DownloadManagerEvent> onEvent;
    private final Cache<String, Optional<DownloadQueueItem>> songStatusCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(2000)
            .build();
    private volatile boolean running = true;

    public DownloadManager(
            DatabaseServerService dbService,
            SongCache songCache,
            Consumer<DownloadManagerEvent> onEvent
    ) {
        this.dbService = dbService;
        this.songCache = songCache;
        this.onEvent = onEvent;
        startQueueProcessor();
    }

    public record DownloadManagerEvent(
            Type type,
            DownloadQueueItem item
    ) {
        public enum Type {
            DOWNLOAD_PENDING,
            DOWNLOAD_STARTED,
            DOWNLOAD_COMPLETED,
            DOWNLOAD_FAILED,
            SONG_CACHED
        }
    }

    private void startQueueProcessor() {
        executor.submit(() -> {
            while (running) {
                try {
                    processQueue();
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in download queue processor", e);
                }
            }
        });
    }

    public Optional<DownloadQueueItem> getSongStatus(String songId) {
        return songStatusCache.get(songId, this::loadSong);
    }
    private Optional<DownloadQueueItem> loadSong(String songId) {
        return dbService.getDownloadQueueItem(songId);
    }

    public void enqueue(SongInfo songInfo) {
        dbService.addToDownloadQueue(songInfo);
        songStatusCache.invalidate(songInfo.id());
        this.publishEvent(songInfo.id());
    }

    public void markAsCached(SongInfo songInfo, String checksum) {
        dbService.addToCacheTracking(songInfo, checksum);
        songStatusCache.invalidate(songInfo.id());
        this.publishEvent(songInfo.id());
    }

    private void publishEvent(String songId) {
        this.getSongStatus(songId).ifPresent(this::publishEvent);
    }
    private void publishEvent(DownloadQueueItem item) {
        var eventOpt = new DownloadManagerEvent(
                switch (item.status()) {
                    case PENDING -> DownloadManagerEvent.Type.DOWNLOAD_PENDING;
                    case DOWNLOADING -> DownloadManagerEvent.Type.DOWNLOAD_STARTED;
                    case COMPLETED -> DownloadManagerEvent.Type.DOWNLOAD_COMPLETED;
                    case FAILED -> DownloadManagerEvent.Type.DOWNLOAD_FAILED;
                    case CACHED -> DownloadManagerEvent.Type.SONG_CACHED;
                },
                item
        );
        this.onEvent.accept(eventOpt);
    }

    private void processQueue() {
        List<DownloadQueueItem> pendingItems = dbService.listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
        for (DownloadQueueItem item : pendingItems) {
            if (item.status() == DownloadStatus.PENDING || item.status() == DownloadStatus.DOWNLOADING) {
                downloadSong(item);
            }
        }
    }

    private void downloadSong(DownloadQueueItem item) {
        try {
            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, item.progress(), null);
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());

            var transcodeInfo = new TranscodeInfo(
                    item.originalBitRate(),
                    item.estimatedBitRate(),
                    Duration.ofSeconds(item.durationSeconds()),
                    item.streamFormat(),
                    URI.create(item.streamUri())
            );

            var cacheSong = new SongCache.CacheSong(
                    item.serverId().toString(),
                    item.songId(),
                    transcodeInfo,
                    "", // originalFileSuffix - maybe not critical if we have transcodeInfo
                    item.originalSize(),
                    (total, count) -> {
                        double progress = (double) count / total;
                        dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, progress, null);
                    }
            );

            var result = songCache.getSong(cacheSong);

            String checksum = null;
            if (result.result() == SongCache.CacheResult.HIT || result.result() == SongCache.CacheResult.MISS) {
                try (var is = result.uri().toURL().openStream()) {
                    checksum = com.github.subsound.utils.Utils.sha256(is);
                } catch (Exception e) {
                    log.warn("Failed to calculate checksum for downloaded song: {}", item.songId(), e);
                }
            }

            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.COMPLETED, 1.0, null, checksum);
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());
            log.info("Downloaded song: {} with checksum: {}", item.songId(), checksum);
        } catch (Exception e) {
            log.error("Failed to download song: {}", item.songId(), e);
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.FAILED, 0.0, e.getMessage());
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());
        }
    }

    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
