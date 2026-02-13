package com.github.subsound.persistence.database;

import java.util.UUID;

public record ScrobbleEntry(
    long id,
    UUID serverId,
    String songId,
    long playedAtMs,
    long createdAtMs,
    ScrobbleStatus status
) {
    public enum ScrobbleStatus {
        PENDING,
        SUBMITTED,
        FAILED
    }
}
