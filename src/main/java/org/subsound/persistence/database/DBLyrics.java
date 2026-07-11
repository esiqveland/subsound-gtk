package org.subsound.persistence.database;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored raw getLyricsBySongId response body for a single song, keyed by (serverId, songId).
 * The raw JSON is re-parsed on read via ServerClient.parseSongLyrics.
 */
public record DBLyrics(
        String songId,
        UUID serverId,
        String rawJson,
        Instant fetchedAt
) {}
