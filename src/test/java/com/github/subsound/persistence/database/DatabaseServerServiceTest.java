package com.github.subsound.persistence.database;

import com.github.subsound.persistence.database.Artist.Biography;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.integration.ServerClient.TranscodeInfo;
import com.github.subsound.integration.ServerClientSongInfoBuilder;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseServerServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testAlbumOperations() throws Exception {
        File dbFile = folder.newFile("test_album_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Album album1 = new Album(
                "album-1",
                serverId,
                "artist-1",
                "Album One",
                10,
                Optional.of(2020),
                "Artist Name",
                Duration.ofMinutes(45),
                Optional.of(now),
                Optional.of("cover-1"),
                now.minus(1, ChronoUnit.DAYS)
        );

        Album album2 = new Album(
                "album-2",
                serverId,
                "artist-1",
                "Album Two",
                12,
                Optional.empty(),
                "Artist Name",
                Duration.ofMinutes(50),
                Optional.empty(),
                Optional.empty(),
                now
        );

        Album album3 = new Album(
                "album-3",
                serverId,
                "artist-2",
                "Album Three",
                8,
                Optional.of(2022),
                "Other Artist",
                Duration.ofMinutes(30),
                Optional.empty(),
                Optional.of("cover-3"),
                now.minus(2, ChronoUnit.DAYS)
        );

        // Test insert
        service.insert(album1);
        service.insert(album2);
        service.insert(album3);

        // Test getAlbumById
        Optional<Album> found = service.getAlbumById("album-1");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get()).usingRecursiveComparison().isEqualTo(album1);

        // Test listAlbumsByArtist
        List<Album> artist1Albums = service.listAlbumsByArtist("artist-1");
        Assertions.assertThat(artist1Albums).hasSize(2);
        Assertions.assertThat(artist1Albums).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrder(album1, album2);

        // Test listAlbumsByAddedAt (should be descending)
        List<Album> albumsByAddedAt = service.listAlbumsByAddedAt();
        Assertions.assertThat(albumsByAddedAt).hasSize(3);
        Assertions.assertThat(albumsByAddedAt).usingRecursiveFieldByFieldElementComparator().containsExactly(album2, album1, album3);
    }

    @Test
    public void testArtistOperations() throws Exception {
        File dbFile = folder.newFile("test_artist_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId1 = UUID.randomUUID();
        UUID serverId2 = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId1, db);
        DatabaseServerService service2 = new DatabaseServerService(serverId2, db);

        Artist artist1 = new Artist(
                "artist-1",
                serverId1,
                "Artist One",
                5,
                Optional.of(Instant.now().truncatedTo(ChronoUnit.MILLIS)),
                Optional.of("cover-1"),
                Optional.of(new Biography("Long bio"))
        );

        var artist2 = new Artist(
                "artist-2",
                serverId1,
                "Artist Two",
                10,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );

        var artist3 = new Artist(
                "artist-3",
                serverId2,
                "Artist Three",
                2,
                Optional.empty(),
                Optional.of("cover-3"),
                Optional.of(new Biography("Long bio"))
        );

        // Test insert
        service.insert(artist1);
        service.insert(artist2);
        service.insert(artist3);

        // Test listArtists for serverId1
        var artistsServer1 = service.listArtists();
        Assertions.assertThat(artistsServer1).hasSize(2);
        Assertions.assertThat(artistsServer1)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(artist1, artist2);

        // Test listArtists for serverId2
        var artistsServer2 = service2.listArtists();
        Assertions.assertThat(artistsServer2).hasSize(1);
        Assertions.assertThat(artistsServer2)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(artist3);

        // Test getArtistById
        var foundArtist = service.getArtistById("artist-1");
        Assertions.assertThat(foundArtist).isPresent();
        Assertions.assertThat(foundArtist.get())
                .usingRecursiveComparison()
                .isEqualTo(artist1);

        // Test getArtistById with non-existent id
        Optional<Artist> notFoundArtist = service.getArtistById("non-existent");
        Assertions.assertThat(notFoundArtist).isEmpty();
    }

    @Test
    public void testSongOperations() throws Exception {
        File dbFile = folder.newFile("test_song_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Song song1 = new Song(
                "song-1",
                serverId,
                "album-1",
                "Song One",
                Optional.of(2020),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(3),
                Optional.of(now),
                Optional.of("cover-1"),
                now
        );

        Song song2 = new Song(
                "song-2",
                serverId,
                "album-1",
                "Song Two",
                Optional.empty(),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(4),
                Optional.empty(),
                Optional.empty(),
                now
        );

        Song song3 = new Song(
                "song-3",
                serverId,
                "album-2",
                "Song Three",
                Optional.of(2022),
                "artist-2",
                "Other Artist",
                Duration.ofMinutes(5),
                Optional.of(now.minus(1, ChronoUnit.HOURS)),
                Optional.of("cover-3"),
                now
        );

        // Test insert
        service.insert(song1);
        service.insert(song2);
        service.insert(song3);

        // Test getSongById
        Optional<Song> found = service.getSongById("song-1");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get()).usingRecursiveComparison().isEqualTo(song1);

        // Test listSongsByAlbumId
        List<Song> album1Songs = service.listSongsByAlbumId("album-1");
        Assertions.assertThat(album1Songs).hasSize(2);
        Assertions.assertThat(album1Songs).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrder(song1, song2);

        // Test listSongsByStarredAt (should be descending)
        List<Song> starredSongs = service.listSongsByStarredAt();
        Assertions.assertThat(starredSongs).hasSize(2);
        Assertions.assertThat(starredSongs).usingRecursiveFieldByFieldElementComparator().containsExactly(song1, song3);
    }

    @Test
    public void testDownloadQueueOperations() throws Exception {
        File dbFile = folder.newFile("test_download_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

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
                        java.net.URI.create("http://example.com/stream")
                ))
                .downloadUri(java.net.URI.create("http://example.com/download"))
                .build();

        // Test addToDownloadQueue
        service.addToDownloadQueue(songInfo);

        // Test listDownloadQueue
        List<DownloadQueueItem> queue = service.listDownloadQueue();
        Assertions.assertThat(queue).hasSize(1);
        DownloadQueueItem item = queue.get(0);
        Assertions.assertThat(item.songId()).isEqualTo("song-1");
        Assertions.assertThat(item.status()).isEqualTo(DownloadQueueItem.DownloadStatus.PENDING);
        Assertions.assertThat(item.streamUri()).isEqualTo("http://example.com/stream");

        // Test updateDownloadProgress
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.DOWNLOADING, 0.5, null);
        queue = service.listDownloadQueue();
        Assertions.assertThat(queue.get(0).status()).isEqualTo(DownloadQueueItem.DownloadStatus.DOWNLOADING);
        Assertions.assertThat(queue.get(0).progress()).isEqualTo(0.5);

        // Test removeFromDownloadQueue
        service.removeFromDownloadQueue("song-1");
        queue = service.listDownloadQueue();
        Assertions.assertThat(queue).isEmpty();
    }
}
