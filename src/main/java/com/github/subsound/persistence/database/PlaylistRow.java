package com.github.subsound.persistence.database;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record PlaylistRow(
        String id,
        UUID serverId,
        String name,
        int songCount,
        Duration duration,
        Optional<String> coverArtId,
        Instant createdAt,
        Instant updatedAt
) {
}
