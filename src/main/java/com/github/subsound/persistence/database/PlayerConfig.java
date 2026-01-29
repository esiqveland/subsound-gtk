package com.github.subsound.persistence.database;

import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Player configuration stored in the database.
 * Uses hardcoded config_key = 1.
 */
@RecordBuilderFull
public record PlayerConfig(
    String serverId,
    @Nullable PlayerStateJson playerState,
    Instant lastUpdated
) implements PlayerConfigBuilder.With {

    public static PlayerConfig defaultConfig(String serverId) {
        return new PlayerConfig(
            serverId,
            PlayerStateJson.defaultState(),
            Instant.now()
        );
    }

    public static PlayerConfig withPlayback(
        String serverId,
        double volume,
        boolean muted,
        String songId,
        long positionMillis,
        long durationMillis
    ) {
        return new PlayerConfig(
            serverId,
            new PlayerStateJson(
                volume,
                muted,
                new PlayerStateJson.PlaybackPosition(songId, positionMillis, durationMillis)
            ),
            Instant.now()
        );
    }
}
