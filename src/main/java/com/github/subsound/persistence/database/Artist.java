package com.github.subsound.persistence.database;

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

    record Biography(
            String original
    ) {}
}
