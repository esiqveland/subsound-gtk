package io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import net.beardbot.subsonic.client.Subsonic;
import net.beardbot.subsonic.client.SubsonicPreferences;

import java.util.List;
import java.util.stream.Stream;

public class SubsonicClient implements ServerClient {
    private final Subsonic client;

    public SubsonicClient(Subsonic client) {
        this.client = client;
    }

    public static SubsonicClient create(SubsonicPreferences preferences) {
        SubsonicClient subsonic = new SubsonicClient(new Subsonic(preferences));
        return subsonic;
    }

    @Override
    public ListArtists getArtists() {
        //this.client.browsing().getIndexes().get(0).getArtists();
        var res = this.client.browsing().getArtists();
        var list = res.stream()
                .flatMap(s -> s.getArtists().stream())
                .map(artist -> new ArtistEntry(
                        artist.getId(),
                        artist.getName(),
                        artist.getAlbumCount()
                )).toList();
        return new ListArtists(list);
    }
}
