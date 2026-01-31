package com.github.subsound.persistence.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Album(
        String id,
        UUID serverId,
        String artistId,
        String name,
        int songCount,
        Optional<Integer> year,
        String artistName,
        Duration duration,
        Optional<Instant> starredAt,
        Optional<String> coverArtId,
        Instant addedAt,
        Optional<String> genre
) {
}
