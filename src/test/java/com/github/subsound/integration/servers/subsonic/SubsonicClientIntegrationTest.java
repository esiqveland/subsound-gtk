package com.github.subsound.integration.servers.subsonic;

import com.github.subsound.configuration.Config;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Map;

/**
 * Integration test that calls a real Navidrome/Subsonic server.
 * Run manually with a configured server in ~/.local/share/subsound-gtk/config.json
 */
@Ignore("Requires a real server connection")
public class SubsonicClientIntegrationTest {

    private SubsonicClient createClient() {
        var config = Config.createDefault();
        var cfg = config.serverConfig;
        return SubsonicClient.create(cfg);
    }

    @Test
    public void printGetArtistJson() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();
        System.out.println("Artist ID: " + firstArtistId);

        String body = client.fetchJsonBody("/rest/getArtist", Map.of("id", firstArtistId));
        System.out.println("=== getArtist response ===");
        System.out.println(body);
    }

    @Test
    public void printGetArtistInfo2Json() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();
        System.out.println("Artist ID: " + firstArtistId);

        String body = client.fetchJsonBody("/rest/getArtistInfo2", Map.of("id", firstArtistId));
        System.out.println("=== getArtistInfo2 response ===");
        System.out.println(body);
    }

    @Test
    public void testGetArtistInfoParsing() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();

        var artistInfo = client.getArtistInfo(firstArtistId);
        System.out.println("Artist: " + artistInfo.name());
        System.out.println("Album count: " + artistInfo.albumCount());
        System.out.println("Albums: " + artistInfo.albums().size());
        System.out.println("Biography: " + artistInfo.biography().original());
        artistInfo.albums().forEach(a -> System.out.println("  - " + a.name() + " (" + a.year().orElse(0) + ")"));
    }

    @Test
    public void testGetArtistWithAlbumsParsing() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();

        var artistInfo = client.getArtistWithAlbums(firstArtistId);
        System.out.println("Artist: " + artistInfo.name());
        System.out.println("Album count: " + artistInfo.albumCount());
        System.out.println("Albums: " + artistInfo.albums().size());
        artistInfo.albums().forEach(a -> System.out.println("  - " + a.name() + " (" + a.year().orElse(0) + ")"));
    }
}
