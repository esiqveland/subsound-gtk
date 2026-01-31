package com.github.subsound.persistence.database;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.AlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistEntry;
import com.github.subsound.integration.ServerClient.ArtistInfo;
import com.github.subsound.integration.ServerClient.Biography;
import com.github.subsound.integration.ServerClient.ListArtists;
import com.github.subsound.integration.ServerClient.SongInfo;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SyncServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ServerClient serverClient;
    private DatabaseServerService databaseServerService;
    private UUID serverId;
    private SyncService syncService;

    @Before
    public void setUp() throws Exception {
        serverClient = mock(ServerClient.class);
        File dbFile = folder.newFile("test_sync.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);
        serverId = UUID.randomUUID();
        databaseServerService = new DatabaseServerService(serverId, db);
        syncService = new SyncService(serverClient, databaseServerService, serverId);
    }

    @Test
    public void testSyncAll() {
        // Mock Artists
        ArtistEntry artistEntry = new ArtistEntry("artist-1", "Artist One", 1, Optional.empty());
        when(serverClient.getArtists()).thenReturn(new ListArtists(List.of(artistEntry)));

        // Mock Artist Info
        ArtistAlbumInfo albumSimple = new ArtistAlbumInfo("album-1", "Album One", 1, "artist-1", "Artist One", Duration.ofMinutes(3), Optional.empty(), Optional.of(2023), Optional.empty(), Optional.empty());
        ArtistInfo artistInfo = new ArtistInfo("artist-1", "Artist One", 1, Optional.empty(), Optional.empty(), List.of(albumSimple), new Biography("bio", "bio", "link"));
        when(serverClient.getArtistInfo("artist-1")).thenReturn(artistInfo);
        when(serverClient.getPlaylists()).thenReturn(new ServerClient.ListPlaylists(List.of()));

        // Mock Album Info
        SongInfo songInfo = mock(SongInfo.class);
        when(songInfo.id()).thenReturn("song-1");
        when(songInfo.title()).thenReturn("Song One");
        when(songInfo.albumId()).thenReturn("album-1");
        when(songInfo.artistId()).thenReturn("artist-1");
        when(songInfo.artist()).thenReturn("Artist One");
        when(songInfo.duration()).thenReturn(Duration.ofMinutes(3));
        when(songInfo.year()).thenReturn(Optional.of(2023));
        when(songInfo.starred()).thenReturn(Optional.empty());
        when(songInfo.coverArt()).thenReturn(Optional.empty());

        AlbumInfo albumInfo = new AlbumInfo("album-1", "Album One", 1, Optional.of(2023), "artist-1", "Artist One", Duration.ofMinutes(3), Optional.empty(), Optional.empty(), List.of(songInfo));
        when(serverClient.getAlbumInfo("album-1")).thenReturn(albumInfo);

        // Run Sync
        syncService.syncAll();

        // Verify
        assertThat(databaseServerService.listArtists()).hasSize(1);
        assertThat(databaseServerService.getArtistById("artist-1")).isPresent();
        assertThat(databaseServerService.listAlbumsByArtist("artist-1")).hasSize(1);
        assertThat(databaseServerService.getAlbumById("album-1")).isPresent();
        assertThat(databaseServerService.listSongsByAlbumId("album-1")).hasSize(1);
        assertThat(databaseServerService.getSongById("song-1")).isPresent();
    }
}
