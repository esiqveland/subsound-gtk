package com.github.subsound.persistence.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Song(
        String id,
        UUID serverId,
        String albumId,
        String name,
        Optional<Integer> year,
        String artistId,
        String artistName,
        Duration duration,
        Optional<Instant> starredAt,
        Optional<String> coverArtId,
        Instant createdAt
) {
}
