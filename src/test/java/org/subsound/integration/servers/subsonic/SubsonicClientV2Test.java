package org.subsound.integration.servers.subsonic;

import org.junit.Test;
import org.subsound.utils.Utils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

public class SubsonicClientV2Test {

    @Test
    public void testParsePingResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "type": "navidrome",
                    "serverVersion": "0.59.0 (cc3cca60)"
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.PingResponseJson.class);
        assertThat(parsed.subsonicResponse.status).isEqualTo("ok");
        assertThat(parsed.subsonicResponse.version).isEqualTo("1.16.1");
        assertThat(parsed.subsonicResponse.type).isEqualTo("navidrome");
        assertThat(parsed.subsonicResponse.serverVersion).isEqualTo("0.59.0 (cc3cca60)");
        assertThat(parsed.getStatus()).isEqualTo("ok");
    }

    @Test
    public void testParseScanStatusResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "type": "navidrome",
                    "serverVersion": "0.59.0 (cc3cca60)",
                    "openSubsonic": true,
                    "scanStatus": {
                      "scanning": false,
                      "count": 3658,
                      "folderCount": 357,
                      "lastScan": "2026-01-25T10:38:16.590557775Z"
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.ScanStatusResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        assertThat(parsed.subsonicResponse.serverVersion).isEqualTo("0.59.0 (cc3cca60)");
        var scan = parsed.subsonicResponse.scanStatus;
        assertThat(scan.scanning()).isFalse();
        assertThat(scan.count()).isEqualTo(3658);
        assertThat(scan.folderCount()).isEqualTo(357);
        assertThat(scan.lastScan()).isEqualTo(Instant.parse("2026-01-25T10:38:16.590557775Z"));
    }

    @Test
    public void testParseGetSongResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "song": {
                      "id": "abc123",
                      "title": "Test Song",
                      "album": "Test Album",
                      "artist": "Test Artist",
                      "track": 3,
                      "year": 2024,
                      "genre": "Rock",
                      "coverArt": "al-xyz",
                      "size": 8675309,
                      "suffix": "mp3",
                      "duration": 245,
                      "bitRate": 320,
                      "playCount": 42,
                      "discNumber": 1,
                      "starred": "2024-07-18T22:20:25Z",
                      "created": "2024-01-01T00:00:00Z",
                      "albumId": "al-xyz",
                      "artistId": "ar-xyz",
                      "isDir": false,
                      "isVideo": false
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetSongResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var song = parsed.subsonicResponse.song;
        assertThat(song.id()).isEqualTo("abc123");
        assertThat(song.title()).isEqualTo("Test Song");
        assertThat(song.album()).isEqualTo("Test Album");
        assertThat(song.artist()).isEqualTo("Test Artist");
        assertThat(song.track()).isEqualTo(3);
        assertThat(song.year()).isEqualTo(2024);
        assertThat(song.genre()).isEqualTo("Rock");
        assertThat(song.size()).isEqualTo(8675309L);
        assertThat(song.suffix()).isEqualTo("mp3");
        assertThat(song.duration()).isEqualTo(245);
        assertThat(song.bitRate()).isEqualTo(320);
        assertThat(song.playCount()).isEqualTo(42L);
        assertThat(song.discNumber()).isEqualTo(1);
        assertThat(song.starred()).isEqualTo(Instant.parse("2024-07-18T22:20:25Z"));
        assertThat(song.created()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(song.albumId()).isEqualTo("al-xyz");
        assertThat(song.artistId()).isEqualTo("ar-xyz");
        assertThat(song.isDir()).isFalse();
        assertThat(song.isVideo()).isFalse();
    }

    @Test
    public void testParseGetSongResponseWithNulls() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "song": {
                      "id": "abc123",
                      "title": "Minimal Song",
                      "album": "Album",
                      "artist": "Artist",
                      "suffix": "flac",
                      "duration": 100,
                      "albumId": "al-1",
                      "artistId": "ar-1"
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetSongResponseJson.class);
        var song = parsed.subsonicResponse.song;
        assertThat(song.id()).isEqualTo("abc123");
        assertThat(song.track()).isNull();
        assertThat(song.year()).isNull();
        assertThat(song.genre()).isNull();
        assertThat(song.bitRate()).isNull();
        assertThat(song.playCount()).isNull();
        assertThat(song.starred()).isNull();
        assertThat(song.created()).isNull();
        assertThat(song.discNumber()).isNull();
        assertThat(song.userRating()).isNull();
        assertThat(song.isDir()).isNull();
        assertThat(song.isVideo()).isNull();
        assertThat(song.size()).isNull();
        assertThat(song.coverArt()).isNull();
    }

    @Test
    public void testParseGetArtistResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "artist": {
                      "id": "ar-1",
                      "name": "Test Artist",
                      "albumCount": 5,
                      "coverArt": "ar-1",
                      "starred": "2024-06-01T12:00:00Z",
                      "album": [
                        {
                          "id": "al-1",
                          "name": "First Album",
                          "artist": "Test Artist",
                          "artistId": "ar-1",
                          "coverArt": "al-1",
                          "songCount": 12,
                          "duration": 3600,
                          "year": 2020,
                          "genre": "Rock"
                        },
                        {
                          "id": "al-2",
                          "name": "Second Album",
                          "artist": "Test Artist",
                          "artistId": "ar-1",
                          "songCount": 8,
                          "duration": 2400,
                          "year": 2022
                        }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetArtistResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var artist = parsed.subsonicResponse.artist;
        assertThat(artist.id()).isEqualTo("ar-1");
        assertThat(artist.name()).isEqualTo("Test Artist");
        assertThat(artist.albumCount()).isEqualTo(5);
        assertThat(artist.starred()).isEqualTo(Instant.parse("2024-06-01T12:00:00Z"));
        assertThat(artist.album()).hasSize(2);
        assertThat(artist.album().getFirst().name()).isEqualTo("First Album");
        assertThat(artist.album().getFirst().year()).isEqualTo(2020);
        assertThat(artist.album().getFirst().genre()).isEqualTo("Rock");
        assertThat(artist.album().get(1).coverArt()).isNull();
        assertThat(artist.album().get(1).genre()).isNull();
    }

    @Test
    public void testParseGetArtistsResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "artists": {
                      "index": [
                        {
                          "name": "A",
                          "artist": [
                            { "id": "ar-1", "name": "ABBA", "albumCount": 10 },
                            { "id": "ar-2", "name": "AC/DC", "albumCount": 15, "coverArt": "ar-2" }
                          ]
                        },
                        {
                          "name": "B",
                          "artist": [
                            { "id": "ar-3", "name": "Beatles", "albumCount": 13, "starred": "2024-01-01T00:00:00Z" }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetArtistsResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var indexes = parsed.subsonicResponse.artists.index;
        assertThat(indexes).hasSize(2);
        assertThat(indexes.getFirst().name()).isEqualTo("A");
        assertThat(indexes.getFirst().artist()).hasSize(2);
        assertThat(indexes.getFirst().artist().getFirst().name()).isEqualTo("ABBA");
        assertThat(indexes.get(1).artist().getFirst().starred()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    public void testParseGetAlbumResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "album": {
                      "id": "al-1",
                      "name": "Test Album",
                      "artist": "Test Artist",
                      "artistId": "ar-1",
                      "coverArt": "al-1",
                      "songCount": 2,
                      "duration": 500,
                      "year": 2023,
                      "genre": "Pop",
                      "song": [
                        {
                          "id": "s-1",
                          "title": "Song One",
                          "album": "Test Album",
                          "artist": "Test Artist",
                          "track": 1,
                          "duration": 250,
                          "suffix": "flac",
                          "bitRate": 1411,
                          "size": 44100000,
                          "albumId": "al-1",
                          "artistId": "ar-1"
                        },
                        {
                          "id": "s-2",
                          "title": "Song Two",
                          "album": "Test Album",
                          "artist": "Test Artist",
                          "track": 2,
                          "duration": 250,
                          "suffix": "flac",
                          "bitRate": 1411,
                          "size": 44100000,
                          "albumId": "al-1",
                          "artistId": "ar-1"
                        }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetAlbumResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var album = parsed.subsonicResponse.album;
        assertThat(album.id()).isEqualTo("al-1");
        assertThat(album.name()).isEqualTo("Test Album");
        assertThat(album.songCount()).isEqualTo(2);
        assertThat(album.year()).isEqualTo(2023);
        assertThat(album.song()).hasSize(2);
        assertThat(album.song().getFirst().title()).isEqualTo("Song One");
        assertThat(album.song().get(1).track()).isEqualTo(2);
    }

    @Test
    public void testParseSearchResult3Response() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "searchResult3": {
                      "artist": [
                        { "id": "ar-1", "name": "Found Artist", "albumCount": 3 }
                      ],
                      "album": [
                        { "id": "al-1", "name": "Found Album", "artist": "Found Artist", "artistId": "ar-1", "songCount": 10, "duration": 3000 }
                      ],
                      "song": [
                        { "id": "s-1", "title": "Found Song", "album": "Found Album", "artist": "Found Artist", "duration": 200, "suffix": "mp3", "albumId": "al-1", "artistId": "ar-1" }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.SearchResult3ResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var sr = parsed.subsonicResponse.searchResult3;
        assertThat(sr.artist()).hasSize(1);
        assertThat(sr.artist().getFirst().name()).isEqualTo("Found Artist");
        assertThat(sr.album()).hasSize(1);
        assertThat(sr.album().getFirst().name()).isEqualTo("Found Album");
        assertThat(sr.song()).hasSize(1);
        assertThat(sr.song().getFirst().title()).isEqualTo("Found Song");
    }

    @Test
    public void testParseGetStarred2Response() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "starred2": {
                      "song": [
                        { "id": "s-1", "title": "Starred Song", "album": "Album", "artist": "Artist", "duration": 180, "suffix": "opus", "albumId": "al-1", "artistId": "ar-1", "starred": "2024-03-15T10:00:00Z" }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetStarred2ResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var starred = parsed.subsonicResponse.starred2;
        assertThat(starred.song()).hasSize(1);
        assertThat(starred.song().getFirst().starred()).isEqualTo(Instant.parse("2024-03-15T10:00:00Z"));
    }

    @Test
    public void testParseGetPlaylistsResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "playlists": {
                      "playlist": [
                        {
                          "id": "pl-1",
                          "name": "My Playlist",
                          "songCount": 25,
                          "duration": 5400,
                          "owner": "user",
                          "public": true,
                          "created": "2024-01-01T00:00:00Z",
                          "changed": "2024-06-15T12:30:00Z",
                          "coverArt": "pl-1"
                        }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetPlaylistsResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var playlists = parsed.subsonicResponse.playlists.playlist;
        assertThat(playlists).hasSize(1);
        var pl = playlists.getFirst();
        assertThat(pl.id).isEqualTo("pl-1");
        assertThat(pl.name).isEqualTo("My Playlist");
        assertThat(pl.songCount).isEqualTo(25);
        assertThat(pl.isPublic).isTrue();
        assertThat(pl.created).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(pl.changed).isEqualTo(Instant.parse("2024-06-15T12:30:00Z"));
    }

    @Test
    public void testParseGetPlaylistResponseWithSongs() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "playlist": {
                      "id": "pl-1",
                      "name": "My Playlist",
                      "songCount": 1,
                      "duration": 200,
                      "owner": "user",
                      "created": "2024-01-01T00:00:00Z",
                      "changed": "2024-06-15T12:30:00Z",
                      "entry": [
                        { "id": "s-1", "title": "Playlist Song", "album": "Album", "artist": "Artist", "duration": 200, "suffix": "mp3", "albumId": "al-1", "artistId": "ar-1" }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetPlaylistResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var pl = parsed.subsonicResponse.playlist;
        assertThat(pl.entry).hasSize(1);
        assertThat(pl.entry.getFirst().title()).isEqualTo("Playlist Song");
    }

    @Test
    public void testParseGetAlbumList2Response() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "albumList2": {
                      "album": [
                        { "id": "al-1", "name": "Recent Album", "artist": "Artist", "artistId": "ar-1", "songCount": 10, "duration": 3000, "year": 2024 },
                        { "id": "al-2", "name": "Another Album", "artist": "Artist2", "artistId": "ar-2", "songCount": 8, "duration": 2000 }
                      ]
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetAlbumList2ResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var albums = parsed.subsonicResponse.albumList2.album();
        assertThat(albums).hasSize(2);
        assertThat(albums.getFirst().name()).isEqualTo("Recent Album");
        assertThat(albums.getFirst().year()).isEqualTo(2024);
        assertThat(albums.get(1).year()).isNull();
    }

    @Test
    public void testParseGetArtistInfo2Response() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "artistInfo2": {
                      "biography": "A great artist <a href='https://example.com' target='_blank'>Read more</a>",
                      "musicBrainzId": "mbid-123",
                      "lastFmUrl": "https://last.fm/artist",
                      "smallImageUrl": "https://img.example.com/s.jpg",
                      "mediumImageUrl": "https://img.example.com/m.jpg",
                      "largeImageUrl": "https://img.example.com/l.jpg"
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.GetArtistInfo2ResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var info = parsed.subsonicResponse.artistInfo2;
        assertThat(info.biography()).contains("A great artist");
        assertThat(info.musicBrainzId()).isEqualTo("mbid-123");
        assertThat(info.lastFmUrl()).isEqualTo("https://last.fm/artist");
    }

    @Test
    public void testParseCreatePlaylistResponse() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "playlist": {
                      "id": "pl-new",
                      "name": "New Playlist",
                      "owner": "user",
                      "public": true,
                      "songCount": 0,
                      "duration": 0,
                      "created": "2024-06-01T00:00:00Z",
                      "changed": "2024-06-01T00:00:00Z"
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.CreatePlaylistResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("ok");
        var pl = parsed.subsonicResponse.playlist;
        assertThat(pl.id).isEqualTo("pl-new");
        assertThat(pl.name).isEqualTo("New Playlist");
        assertThat(pl.songCount).isEqualTo(0);
    }

    @Test
    public void testParseErrorStatus() {
        String json = """
                {
                  "subsonic-response": {
                    "status": "failed",
                    "version": "1.16.1"
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.PingResponseJson.class);
        assertThat(parsed.getStatus()).isEqualTo("failed");
        try {
            parsed.checkOk("/rest/ping");
            assertThat(false).as("should have thrown").isTrue();
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("Subsonic error");
            assertThat(e.getMessage()).contains("failed");
        }
    }

    @Test
    public void testMd5Auth() {
        // Verify md5 produces correct hash for known input
        // md5("sesame" + "c19b2d") = "26719a1196d2a940705a59634eb18eab"
        String result = SubsonicClientV2.md5("sesamec19b2d");
        assertThat(result).isEqualTo("26719a1196d2a940705a59634eb18eab");
    }

    @Test
    public void testNavidromeTimestampWithNanos() {
        // Navidrome sends timestamps with nanosecond precision
        String json = """
                {
                  "subsonic-response": {
                    "status": "ok",
                    "version": "1.16.1",
                    "scanStatus": {
                      "scanning": false,
                      "count": 100,
                      "lastScan": "2024-07-18T22:20:25.220976486Z"
                    }
                  }
                }
                """;
        var parsed = Utils.fromJson(json, SubsonicClientV2.ScanStatusResponseJson.class);
        var lastScan = parsed.subsonicResponse.scanStatus.lastScan();
        assertThat(lastScan).isNotNull();
        assertThat(lastScan.getEpochSecond()).isEqualTo(Instant.parse("2024-07-18T22:20:25.220976486Z").getEpochSecond());
    }
}
