package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.integration.ServerClientSongInfoBuilder;
import com.github.subsound.persistence.DownloadManager.DownloadManagerEvent;
import com.github.subsound.persistence.database.Database;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.DownloadQueueItem;
import com.github.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.assertj.core.api.Assertions;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.net.URI;

public class DownloadManagerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testDownloadQueueFlow() throws Exception {
        File dataDir = folder.newFolder("data");
        File dbFile = new File(dataDir, "test.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        
        UUID serverId = UUID.randomUUID();
        DatabaseServerService dbService = new DatabaseServerService(serverId, db);
        SongCache songCache = new SongCache(dataDir.toPath());
        var eventList = new ArrayList<DownloadManagerEvent>();
        DownloadManager downloadManager = new DownloadManager(dbService, songCache, eventList::add);
        
        SongInfo songInfo = ServerClientSongInfoBuilder.builder()
                .id("song-1")
                .title("Song One")
                .artistId("artist-1")
                .artist("Artist Name")
                .albumId("album-1")
                .album("Album Name")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .suffix("mp3")
                .transcodeInfo(new TranscodeInfo(
                        Optional.of(320),
                        128,
                        Duration.ofMinutes(3),
                        "mp3",
                        URI.create("file:///dev/null") // Use a file URI for testing if possible or mock
                ))
                .downloadUri(URI.create("file:///dev/null"))
                .build();
        
        // Enqueue
        downloadManager.enqueue(songInfo);
        
        // Wait for it to be processed
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10000) {
            var items = dbService.listDownloadQueue();
            if (items.stream().allMatch(item -> item.status() != DownloadQueueItem.DownloadStatus.PENDING)) {
                break;
            }
            Thread.sleep(100);
        }

        var items = dbService.listDownloadQueue();
        Assertions.assertThat(items).allMatch(item -> item.status() != DownloadQueueItem.DownloadStatus.PENDING);
        
        downloadManager.stop();
    }
}
