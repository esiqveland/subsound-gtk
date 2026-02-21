package com.github.subsound.persistence;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.integration.ServerClientSongInfoBuilder;
import com.github.subsound.persistence.DownloadManager.DownloadManagerEvent;
import com.github.subsound.persistence.MockMusicServer.SampleSong;
import com.github.subsound.persistence.database.Database;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.DownloadQueueItem;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.github.subsound.ui.views.TestPlayerPage.loadSamples;

public class DownloadManagerTest {
    private static List<SampleSong> samples = sampleSongs();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testDownloadQueueFlow() throws Exception {
        File dataDir = folder.newFolder("data");
        File dbFile = new File(dataDir, "test.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        
        UUID serverId = UUID.randomUUID();
        DatabaseServerService dbService = new DatabaseServerService(serverId, db);
        var songId = samples.stream().findAny().orElseThrow().songId();
        SongInfo songInfo = ServerClientSongInfoBuilder.builder()
                .id(songId)
                .title("Song One")
                .artistId("artist-1")
                .artist("Artist Name")
                .albumId("album-1")
                .album("Album Name")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .suffix("mp3")
                .transcodeInfo(new TranscodeInfo(
                        songId,
                        Optional.of(320),
                        128,
                        Duration.ofMinutes(3),
                        "mp3"
                ))
                .downloadUri(URI.create("file:///dev/null"))
                .build();

        var eventList = new ArrayList<DownloadManagerEvent>();
        SongCache songCache = new SongCache(dataDir.toPath(), transcodeInfo -> this.mockMusicServer.getTranscodeStream(transcodeInfo.songId()));
        DownloadManager downloadManager = new DownloadManager(dbService, songCache, eventList::add);
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
