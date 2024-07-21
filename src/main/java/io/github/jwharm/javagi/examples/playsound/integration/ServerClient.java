package io.github.jwharm.javagi.examples.playsound.integration;

import java.util.List;

public interface ServerClient {

    ListArtists getArtists();

    record ArtistEntry(
            String id,
            String name,
            int albumCount
    ) {
    }

    record ListArtists(
            List<ArtistEntry> list
    ) {
    }
}
