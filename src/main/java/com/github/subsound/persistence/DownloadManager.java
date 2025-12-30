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

    public void enqueue(SongInfo songInfo) {
        dbService.addToDownloadQueue(songInfo);
    }

    }
}
