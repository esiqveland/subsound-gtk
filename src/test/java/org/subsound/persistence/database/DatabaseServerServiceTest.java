package org.subsound.persistence.database;

import org.subsound.integration.ServerClient;
import org.subsound.persistence.database.Artist.Biography;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URI;
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
                now.minus(1, ChronoUnit.DAYS),
                Optional.of("Rock")
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
                now,
                Optional.empty()
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
                now.minus(2, ChronoUnit.DAYS),
                Optional.empty()
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
        DBSong song1 = new DBSong(
                "song-1",
                serverId,
                "album-1",
                "Album One",
                "Song One",
                Optional.of(2020),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(3),
                Optional.of(now),
                Optional.of("cover-1"),
                now,
                Optional.of(1),
                Optional.of(1),
                Optional.of(320),
                5000000L,
                "Rock",
                "mp3",
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        DBSong song2 = new DBSong(
                "song-2",
                serverId,
                "album-1",
                "Album One",
                "Song Two",
                Optional.empty(),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(4),
                Optional.empty(),
                Optional.empty(),
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0L,
                "",
                "",
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        DBSong song3 = new DBSong(
                "song-3",
                serverId,
                "album-2",
                "Album Two",
                "Song Three",
                Optional.of(2022),
                "artist-2",
                "Other Artist",
                Duration.ofMinutes(5),
                Optional.of(now.minus(1, ChronoUnit.HOURS)),
                Optional.of("cover-3"),
                now,
                Optional.of(3),
                Optional.empty(),
                Optional.of(256),
                8000000L,
                "Pop",
                "flac",
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        // Test insert
        service.insert(song1);
        service.insert(song2);
        service.insert(song3);

        // Test getSongById
        Optional<DBSong> found = service.getSongById("song-1");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get()).usingRecursiveComparison().isEqualTo(song1);

        // Test listSongsByAlbumId
        List<DBSong> album1Songs = service.listSongsByAlbumId("album-1");
        Assertions.assertThat(album1Songs).hasSize(2);
        Assertions.assertThat(album1Songs).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrder(song1, song2);

        // Test listSongsByStarredAt (should be descending)
        List<DBSong> starredSongs = service.listSongsByStarredAt();
        Assertions.assertThat(starredSongs).hasSize(2);
        Assertions.assertThat(starredSongs).usingRecursiveFieldByFieldElementComparator().containsExactly(song1, song3);
    }

    @Test
    public void testSongArtistsAndMoodsRoundTrip() throws Exception {
        File dbFile = folder.newFile("test_song_artists_moods.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var artists = List.of(
                new ServerClient.ArtistId("a1", "Main Artist"),
                new ServerClient.ArtistId("a2", "Featured")
        );
        var albumArtists = List.of(new ServerClient.ArtistId("a1", "Main Artist"));
        var moods = List.of("Energetic", "Happy");

        DBSong song = new DBSong(
                "song-multi", serverId, "album-1", "Album One", "Song Multi",
                Optional.of(2024), "a1", "Main Artist",
                Duration.ofMinutes(3),
                Optional.empty(), Optional.empty(), now,
                Optional.of(1), Optional.of(1), Optional.of(320),
                5000000L, "Rock", "mp3",
                Optional.of(artists), Optional.of(albumArtists), moods
        );

        service.insert(song);

        Optional<DBSong> found = service.getSongById("song-multi");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get().artists()).contains(artists);
        Assertions.assertThat(found.get().albumArtists()).contains(albumArtists);
        Assertions.assertThat(found.get().moods()).containsExactlyElementsOf(moods);

        // Empty/missing values should round-trip as Optional.empty / List.of()
        DBSong bare = new DBSong(
                "song-bare", serverId, "album-1", "Album One", "Song Bare",
                Optional.empty(), "a1", "Main Artist",
                Duration.ofMinutes(2),
                Optional.empty(), Optional.empty(), now,
                Optional.empty(), Optional.empty(), Optional.empty(),
                0L, "", "",
                Optional.empty(), Optional.empty(), List.of()
        );
        service.insert(bare);
        Optional<DBSong> foundBare = service.getSongById("song-bare");
        Assertions.assertThat(foundBare).isPresent();
        Assertions.assertThat(foundBare.get().artists()).isEmpty();
        Assertions.assertThat(foundBare.get().albumArtists()).isEmpty();
        Assertions.assertThat(foundBare.get().moods()).isEmpty();
    }

    @Test
    public void testPlaylistRemoveSong() throws Exception {
        File dbFile = folder.newFile("test_playlist_remove.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        String playlistId = "playlist-1";

        // Insert 5 songs at positions 0–4
        service.insertPlaylistSong(playlistId, "song-a", 0);
        service.insertPlaylistSong(playlistId, "song-b", 1);
        service.insertPlaylistSong(playlistId, "song-c", 2);
        service.insertPlaylistSong(playlistId, "song-d", 3);
        service.insertPlaylistSong(playlistId, "song-e", 4);

        // Remove the middle song; remaining songs should be renumbered 0–3
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(new ServerClient.SongRemoval(2, "song-c"))
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-a", "song-b", "song-d", "song-e");

        // Guard: wrong position for song-b (now at 1, not 0) — nothing removed
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(new ServerClient.SongRemoval(0, "song-b"))
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-a", "song-b", "song-d", "song-e");

        // Batch removal: remove song-a (pos 0) and song-d (pos 2) in one request;
        // survivors song-b and song-e should be renumbered 0 and 1
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(
                        new ServerClient.SongRemoval(0, "song-a"),
                        new ServerClient.SongRemoval(2, "song-d")
                )
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-b", "song-e");
    }

    @Test
    public void testPlaylistDuplicateSongOrder() throws Exception {
        File dbFile = folder.newFile("test_playlist_duplicates.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        String playlistId = "playlist-dup";

        // Add 3 entries of song1
        service.insertPlaylistSong(playlistId, "song1", 0);
        service.insertPlaylistSong(playlistId, "song1", 1);
        service.insertPlaylistSong(playlistId, "song1", 2);

        // Add 2 entries of song2
        service.insertPlaylistSong(playlistId, "song2", 3);
        service.insertPlaylistSong(playlistId, "song2", 4);

        // Add 4 more entries of song1
        service.insertPlaylistSong(playlistId, "song1", 5);
        service.insertPlaylistSong(playlistId, "song1", 6);
        service.insertPlaylistSong(playlistId, "song1", 7);
        service.insertPlaylistSong(playlistId, "song1", 8);

        // Verify the full order is preserved: 3×song1, 2×song2, 4×song1
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly(
                        "song1", "song1", "song1",
                        "song2", "song2",
                        "song1", "song1", "song1", "song1"
                );
    }

    @Test
    public void testDownloadQueueOperations() throws Exception {
        File dbFile = folder.newFile("test_download_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        var songId = "song-1";
        SongInfo songInfo = ServerClientSongInfoBuilder.builder()
                .id(songId)
                .title("Song One")
                .mainArtist(new ServerClient.ArtistId("artist-1", "Artist Name"))
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
                .downloadUri(URI.create("http://example.com/download"))
                .build();

        // Test addToDownloadQueue
        service.addToDownloadQueue(songInfo);

        // Test listDownloadQueue
        List<DownloadQueueItem> queue = service.listDownloadQueue();
        Assertions.assertThat(queue).hasSize(1);
        DownloadQueueItem item = queue.get(0);
        Assertions.assertThat(item.songId()).isEqualTo("song-1");
        Assertions.assertThat(item.status()).isEqualTo(DownloadQueueItem.DownloadStatus.PENDING);

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

    @Test
    public void testReEnqueueFailedDownloadResetsToPending() throws Exception {
        File dbFile = folder.newFile("test_download_retry.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        SongInfo songInfo = downloadableSong("song-1");

        service.addToDownloadQueue(songInfo);
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.FAILED, 0.0, "boom");
        Assertions.assertThat(service.getDownloadQueueItem("song-1").orElseThrow().status())
                .isEqualTo(DownloadQueueItem.DownloadStatus.FAILED);

        // Re-adding a FAILED song must reset it to PENDING so it gets retried
        service.addToDownloadQueue(songInfo);
        DownloadQueueItem item = service.getDownloadQueueItem("song-1").orElseThrow();
        Assertions.assertThat(item.status()).isEqualTo(DownloadQueueItem.DownloadStatus.PENDING);
        Assertions.assertThat(item.errorMessage()).isNull();

        // A DOWNLOADING row must not be reset by a duplicate enqueue
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.DOWNLOADING, 0.5, null);
        service.addToDownloadQueue(songInfo);
        Assertions.assertThat(service.getDownloadQueueItem("song-1").orElseThrow().status())
                .isEqualTo(DownloadQueueItem.DownloadStatus.DOWNLOADING);
    }

    @Test
    public void testStatusUpdateWithoutChecksumPreservesStoredChecksum() throws Exception {
        File dbFile = folder.newFile("test_download_checksum.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        SongInfo songInfo = downloadableSong("song-1");

        service.addToDownloadQueue(songInfo);
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.COMPLETED, 1.0, null, "abc123");
        Assertions.assertThat(service.getDownloadQueueItem("song-1").orElseThrow().checksum())
                .contains("abc123");

        // Demoting COMPLETED -> CACHED (file unchanged on disk) must keep the checksum
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.CACHED, 1.0, null);
        DownloadQueueItem item = service.getDownloadQueueItem("song-1").orElseThrow();
        Assertions.assertThat(item.status()).isEqualTo(DownloadQueueItem.DownloadStatus.CACHED);
        Assertions.assertThat(item.checksum()).contains("abc123");

        // The explicit-checksum overload still overwrites
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.COMPLETED, 1.0, null, "def456");
        Assertions.assertThat(service.getDownloadQueueItem("song-1").orElseThrow().checksum())
                .contains("def456");
    }

    @Test
    public void testLyricsOperations() throws Exception {
        File dbFile = folder.newFile("test_lyrics_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        UUID otherServerId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        DatabaseServerService otherService = new DatabaseServerService(otherServerId, db);

        // unknown songId -> empty
        Assertions.assertThat(service.getLyricsBySongId("song-1")).isEmpty();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String raw1 = "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\"}}";
        service.upsertLyrics(new DBLyrics("song-1", serverId, raw1, now));

        Optional<DBLyrics> stored = service.getLyricsBySongId("song-1");
        Assertions.assertThat(stored).isPresent();
        Assertions.assertThat(stored.get().songId()).isEqualTo("song-1");
        Assertions.assertThat(stored.get().serverId()).isEqualTo(serverId);
        Assertions.assertThat(stored.get().rawJson()).isEqualTo(raw1);
        Assertions.assertThat(stored.get().fetchedAt()).isEqualTo(now);

        // upsert replaces the existing row
        String raw2 = "{\"subsonic-response\":{\"status\":\"ok\",\"version\":\"1.16.1\",\"lyricsList\":{}}}";
        Instant later = now.plus(1, ChronoUnit.HOURS);
        service.upsertLyrics(new DBLyrics("song-1", serverId, raw2, later));
        Optional<DBLyrics> replaced = service.getLyricsBySongId("song-1");
        Assertions.assertThat(replaced).isPresent();
        Assertions.assertThat(replaced.get().rawJson()).isEqualTo(raw2);
        Assertions.assertThat(replaced.get().fetchedAt()).isEqualTo(later);

        // rows are scoped by serverId
        Assertions.assertThat(otherService.getLyricsBySongId("song-1")).isEmpty();

        // clearLyrics removes only this server's rows
        otherService.upsertLyrics(new DBLyrics("song-2", otherServerId, raw1, now));
        service.clearLyrics();
        Assertions.assertThat(service.getLyricsBySongId("song-1")).isEmpty();
        Assertions.assertThat(otherService.getLyricsBySongId("song-2")).isPresent();
    }

    @Test
    public void testOfflineSearchNorwegianMatching() throws Exception {
        File dbFile = folder.newFile("test_search.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        UUID serverId = UUID.randomUUID();
        UUID otherServerId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        DatabaseServerService otherService = new DatabaseServerService(otherServerId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        service.insert(searchTestArtist("artist-1", serverId, "Øystein Sunde"));
        otherService.insert(searchTestArtist("artist-2", otherServerId, "Øystein Kopi"));
        service.insert(searchTestAlbum("album-1", serverId, "artist-1", "På Sangens Vinger", "Øystein Sunde", now));
        service.insert(searchTestSong("song-1", serverId, "album-1", "Bårds Vise", "artist-1", "Øystein Sunde", "På Sangens Vinger", now));

        // every spelling of Øystein finds the artist, including as-you-type prefixes
        for (String query : List.of("øystein", "oystein", "oeystein", "Øys", "oys")) {
            Assertions.assertThat(service.searchArtists(query, 20))
                    .as("artist query: %s", query)
                    .extracting(Artist::name)
                    .containsExactly("Øystein Sunde");
        }
        // results are scoped to the service's server
        Assertions.assertThat(otherService.searchArtists("oystein", 20))
                .extracting(Artist::name)
                .containsExactly("Øystein Kopi");

        // every spelling of Bård finds the song
        for (String query : List.of("bård", "baard", "bard")) {
            Assertions.assertThat(service.searchSongs(query, 50))
                    .as("song query: %s", query)
                    .extracting(DBSong::name)
                    .containsExactly("Bårds Vise");
        }
        // songs also match on artist and album name, and multi-word queries are ANDed
        Assertions.assertThat(service.searchSongs("sunde", 50)).hasSize(1);
        Assertions.assertThat(service.searchSongs("vinger baards", 50)).hasSize(1);
        Assertions.assertThat(service.searchSongs("vinger nothere", 50)).isEmpty();
        // albums match on name and artist name
        Assertions.assertThat(service.searchAlbums("sangens", 20)).hasSize(1);
        Assertions.assertThat(service.searchAlbums("oystein", 20)).hasSize(1);
        // blank or symbol-only queries return nothing instead of failing
        Assertions.assertThat(service.searchSongs("   ", 50)).isEmpty();
        Assertions.assertThat(service.searchSongs("\"(*", 50)).isEmpty();
    }

    @Test
    public void testOfflineSearchCjkBehavior() throws Exception {
        File dbFile = folder.newFile("test_search_cjk.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        service.insert(searchTestArtist("artist-iu", serverId, "아이유"));
        service.insert(searchTestSong("song-ko", serverId, "album-1", "좋은 날", "artist-iu", "아이유", "Real", now));
        service.insert(searchTestSong("song-zh", serverId, "album-2", "月亮代表我的心", "artist-teresa", "鄧麗君", "淡淡幽情", now));
        service.insert(searchTestSong("song-mixed", serverId, "album-3", "BTS의 노래", "artist-bts", "BTS", "Proof", now));

        // Korean: space-separated words match on the word and on word prefixes
        Assertions.assertThat(service.searchArtists("아이유", 20)).extracting(Artist::name).containsExactly("아이유");
        Assertions.assertThat(service.searchArtists("아이", 20)).extracting(Artist::name).containsExactly("아이유");
        Assertions.assertThat(service.searchSongs("좋은", 50)).extracting(DBSong::name).containsExactly("좋은 날");

        // Chinese/Japanese: an unspaced title is a single FTS token, so title-start prefixes match...
        Assertions.assertThat(service.searchSongs("月亮", 50)).extracting(DBSong::name).containsExactly("月亮代表我的心");
        Assertions.assertThat(service.searchSongs("鄧麗君", 50)).extracting(DBSong::name).containsExactly("月亮代表我的心");
        // ...but words from the middle of the title do not. Known limitation; the fix, if ever
        // wanted, is character-bigram emission in SearchNormalizer plus a search_text re-backfill
        // migration.
        Assertions.assertThat(service.searchSongs("代表", 50)).isEmpty();

        // Mixed script: Latin and Hangul runs stay contiguous, prefix search still applies
        Assertions.assertThat(service.searchSongs("bts", 50)).extracting(DBSong::name).containsExactly("BTS의 노래");
    }

    @Test
    public void testSearchIndexStaysInSyncAcrossReplaceAndDelete() throws Exception {
        File dbFile = folder.newFile("test_search_sync.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        service.insert(searchTestSong("song-1", serverId, "album-1", "Original Title", "artist-1", "Some Artist", "Some Album", now));
        Assertions.assertThat(service.searchSongs("original", 50)).hasSize(1);

        // INSERT OR REPLACE of the same key must drop the old index entry (requires
        // recursive_triggers so the conflict-delete fires the FTS delete trigger)
        service.insert(searchTestSong("song-1", serverId, "album-1", "Replaced Title", "artist-1", "Some Artist", "Some Album", now));
        Assertions.assertThat(service.searchSongs("original", 50)).isEmpty();
        Assertions.assertThat(service.searchSongs("replaced", 50)).hasSize(1);

        // bulk deletes go through the triggers too
        service.deleteAllSongs();
        Assertions.assertThat(service.searchSongs("replaced", 50)).isEmpty();

        // verify the FTS index is consistent with the content table; raises SQLITE_CORRUPT_VTAB if not
        try (var conn = db.openConnection(); var stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO songs_fts(songs_fts, rank) VALUES('integrity-check', 1)");
        }
    }

    private static Artist searchTestArtist(String id, UUID serverId, String name) {
        return new Artist(id, serverId, name, 1, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Album searchTestAlbum(String id, UUID serverId, String artistId, String name, String artistName, Instant now) {
        return new Album(id, serverId, artistId, name, 1, Optional.empty(), artistName,
                Duration.ofMinutes(40), Optional.empty(), Optional.empty(), now, Optional.empty());
    }

    private static DBSong searchTestSong(String id, UUID serverId, String albumId, String title, String artistId, String artistName, String albumName, Instant now) {
        return new DBSong(id, serverId, albumId, albumName, title, Optional.empty(), artistId, artistName,
                Duration.ofMinutes(3), Optional.empty(), Optional.empty(), now,
                Optional.empty(), Optional.empty(), Optional.empty(), 1000L, "", "mp3",
                Optional.empty(), Optional.empty(), List.of());
    }

    private static SongInfo downloadableSong(String songId) {
        return ServerClientSongInfoBuilder.builder()
                .id(songId)
                .title("Song " + songId)
                .mainArtist(new ServerClient.ArtistId("artist-1", "Artist Name"))
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
                .downloadUri(URI.create("http://example.com/download"))
                .build();
    }
}
