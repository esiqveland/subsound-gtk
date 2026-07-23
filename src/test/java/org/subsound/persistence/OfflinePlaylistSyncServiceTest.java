package org.subsound.persistence;

import org.subsound.app.state.NetworkMonitoring.NetworkState;
import org.subsound.app.state.NetworkMonitoring.NetworkStatus;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ListPlaylists;
import org.subsound.integration.ServerClient.ListStarred;
import org.subsound.integration.ServerClient.Playlist;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.PlaylistSimple;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DownloadSource;
import org.subsound.persistence.database.OfflinePlaylistDao;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OfflinePlaylistSyncServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private OfflinePlaylistDao newDao() throws Exception {
        File dbFile = folder.newFile("test_offline_" + UUID.randomUUID() + ".db");
        return new OfflinePlaylistDao(new Database("jdbc:sqlite:" + dbFile.getAbsolutePath()));
    }

    private OfflinePlaylistSyncService service(
            OfflinePlaylistDao dao, UUID serverId, ServerClient client,
            DownloadManager downloadManager, NetworkStatus status
    ) {
        return new OfflinePlaylistSyncService(
                dao, serverId, () -> client, () -> new NetworkState(status), downloadManager,
                false // do not auto-start; drive processPending() directly
        );
    }

    private static SongInfo song(String id, Instant starredAt) {
        return ServerClientSongInfoBuilder.builder()
                .id(id)
                .title("Title " + id)
                .mainArtist(new ServerClient.ArtistId("artist-1", "Artist"))
                .albumId("album-1")
                .album("Album")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .suffix("mp3")
                .genre("rock")
                .moods(List.of())
                .playCount(0L)
                .starred(Optional.ofNullable(starredAt))
                .transcodeInfo(new TranscodeInfo(id, Optional.of(320), 320, Duration.ofMinutes(3), "mp3"))
                .downloadUri(URI.create("file:///dev/null"))
                .build();
    }

    private static PlaylistSimple simple(String id, Instant changedAt) {
        return new PlaylistSimple(id, "P " + id, PlaylistKind.NORMAL, Optional.empty(), 2, changedAt, changedAt);
    }

    @Test
    public void normalPlaylistFirstSyncEnqueuesAllAndSetsWatermark() throws Exception {
        var dao = newDao();
        var serverId = UUID.randomUUID();
        var changedAt = Instant.parse("2026-01-01T00:00:00Z");
        dao.enable(serverId, "pl-1", PlaylistKind.NORMAL, Instant.now());

        var s1 = song("s1", null);
        var s2 = song("s2", null);
        var client = mock(ServerClient.class);
        when(client.getPlaylists()).thenReturn(new ListPlaylists(List.of(simple("pl-1", changedAt))));
        when(client.getPlaylist("pl-1")).thenReturn(new Playlist(
                "pl-1", "P pl-1", PlaylistKind.NORMAL, Optional.empty(), 2, changedAt, changedAt, List.of(s1, s2)));

        var dm = mock(DownloadManager.class);
        service(dao, serverId, client, dm, NetworkStatus.ONLINE).processPending();

        verify(dm).enqueue(s1, DownloadSource.PLAYLIST_SYNC);
        verify(dm).enqueue(s2, DownloadSource.PLAYLIST_SYNC);
        assertThat(dao.find(serverId, "pl-1").orElseThrow().watermark()).contains(changedAt);
    }

    @Test
    public void normalPlaylistUnchangedIsSkipped() throws Exception {
        var dao = newDao();
        var serverId = UUID.randomUUID();
        var changedAt = Instant.parse("2026-01-01T00:00:00Z");
        dao.enable(serverId, "pl-1", PlaylistKind.NORMAL, Instant.now());
        // Pretend we already synced up to changedAt.
        dao.updateWatermark(serverId, "pl-1", changedAt, Instant.now());

        var client = mock(ServerClient.class);
        when(client.getPlaylists()).thenReturn(new ListPlaylists(List.of(simple("pl-1", changedAt))));

        var dm = mock(DownloadManager.class);
        service(dao, serverId, client, dm, NetworkStatus.ONLINE).processPending();

        verify(client, never()).getPlaylist("pl-1");
        verify(dm, never()).enqueue(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    public void starredEnqueuesOnlySongsAtOrAfterWatermark() throws Exception {
        var dao = newDao();
        var serverId = UUID.randomUUID();
        var t1 = Instant.parse("2026-01-01T00:00:00Z");
        var t2 = Instant.parse("2026-02-01T00:00:00Z");
        dao.enable(serverId, OfflinePlaylistDao.STARRED_SENTINEL, PlaylistKind.STARRED, Instant.now());
        // Already synced up to t1: only the newer t2 song should enqueue.
        dao.updateWatermark(serverId, OfflinePlaylistDao.STARRED_SENTINEL, t1, Instant.now());

        var older = song("old", t1);
        var newer = song("new", t2);
        var client = mock(ServerClient.class);
        when(client.getStarred()).thenReturn(new ListStarred(List.of(older, newer)));

        var dm = mock(DownloadManager.class);
        service(dao, serverId, client, dm, NetworkStatus.ONLINE).processPending();

        // >= watermark: the t1 song (== watermark) and the t2 song both enqueue; a strictly-older
        // one would not. Here both are >= t1, so both enqueue.
        verify(dm).enqueue(older, DownloadSource.PLAYLIST_SYNC);
        verify(dm).enqueue(newer, DownloadSource.PLAYLIST_SYNC);
        assertThat(dao.find(serverId, OfflinePlaylistDao.STARRED_SENTINEL).orElseThrow().watermark()).contains(t2);
    }

    @Test
    public void offlineSkipsEverything() throws Exception {
        var dao = newDao();
        var serverId = UUID.randomUUID();
        dao.enable(serverId, "pl-1", PlaylistKind.NORMAL, Instant.now());

        var client = mock(ServerClient.class);
        var dm = mock(DownloadManager.class);
        service(dao, serverId, client, dm, NetworkStatus.OFFLINE).processPending();

        verify(client, never()).getPlaylists();
        verify(dm, never()).enqueue(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    public void reEnableClearsWatermarkForcingFullResync() throws Exception {
        var dao = newDao();
        var serverId = UUID.randomUUID();
        var changedAt = Instant.parse("2026-01-01T00:00:00Z");
        var s1 = song("s1", null);
        var s2 = song("s2", null);
        var client = mock(ServerClient.class);
        when(client.getPlaylists()).thenReturn(new ListPlaylists(List.of(simple("pl-1", changedAt))));
        when(client.getPlaylist("pl-1")).thenReturn(new Playlist(
                "pl-1", "P pl-1", PlaylistKind.NORMAL, Optional.empty(), 2, changedAt, changedAt, List.of(s1, s2)));
        var dm = mock(DownloadManager.class);

        // First full sync.
        dao.enable(serverId, "pl-1", PlaylistKind.NORMAL, Instant.now());
        service(dao, serverId, client, dm, NetworkStatus.ONLINE).processPending();

        // on -> off -> on: re-enable must clear the watermark and re-enqueue everything.
        dao.disable(serverId, "pl-1");
        dao.enable(serverId, "pl-1", PlaylistKind.NORMAL, Instant.now());
        assertThat(dao.find(serverId, "pl-1").orElseThrow().watermark()).isEmpty();

        service(dao, serverId, client, dm, NetworkStatus.ONLINE).processPending();

        var captor = ArgumentCaptor.forClass(SongInfo.class);
        verify(dm, times(4)).enqueue(captor.capture(), org.mockito.Mockito.eq(DownloadSource.PLAYLIST_SYNC));
        assertThat(captor.getAllValues()).containsExactly(s1, s2, s1, s2);
    }
}
