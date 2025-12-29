package com.github.subsound.persistence;

import com.github.subsound.persistence.database.DatabaseServerService;

public class DownloadManager {
    private final DatabaseServerService dbService;
    private final SongCache songCache;

    public DownloadManager(DatabaseServerService dbService, SongCache songCache) {
        this.dbService = dbService;
        this.songCache = songCache;
    }
}
