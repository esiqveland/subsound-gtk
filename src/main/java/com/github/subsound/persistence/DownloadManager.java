package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.DownloadQueueItem;
import com.github.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import com.github.subsound.persistence.database.Song;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DownloadManager {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final DatabaseServerService dbService;
    private final SongCache songCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    public DownloadManager(DatabaseServerService dbService, SongCache songCache) {
        this.dbService = dbService;
        this.songCache = songCache;
        //startQueueProcessor();
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

    public void enqueue(SongInfo songInfo) {
        dbService.addToDownloadQueue(songInfo);
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
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, item.progress(), null);

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

            dbService.updateDownloadProgress(item.songId(), DownloadStatus.COMPLETED, 1.0, null, checksum);
            log.info("Downloaded song: {} with checksum: {}", item.songId(), checksum);
        } catch (Exception e) {
            log.error("Failed to download song: {}", item.songId(), e);
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.FAILED, 0.0, e.getMessage());
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
