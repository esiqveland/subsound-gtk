package org.subsound.persistence.database;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Artist(
        String id,
        UUID serverId,
        String name,
        int albumCount,
        Optional<Instant> starredAt,
        Optional<String> coverArtId,
        Optional<Biography> biography
) {

    public record Biography(
            String original
    ) {}
}
