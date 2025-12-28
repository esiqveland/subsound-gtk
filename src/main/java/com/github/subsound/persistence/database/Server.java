package com.github.subsound.persistence.database;

import com.github.subsound.integration.ServerClient.ServerType;

import java.time.Instant;
import java.util.UUID;

public record Server(
        UUID id,
        boolean isPrimary,
        ServerType serverType,
        String serverUrl,
        String username,
        Instant createdAt
) {
}
