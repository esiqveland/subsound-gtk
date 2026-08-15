package org.subsound.sound;

import io.soabase.recordbuilder.core.RecordBuilderFull;

import java.time.Instant;
import java.util.Optional;

// a public read-only view of the player state
@RecordBuilderFull
public record PlayerState(
        PlayerStates state,
        double volume,
        boolean muted,
        Optional<Instant> playbackStartedAt,
        // Wall-clock instant corresponding to stream position = 0 for the current segment.
        // While PLAYING, current position ≈ now - positionAnchorAt. Moves on seek so UI can
        // extrapolate locally without waiting for position-notifications.
        Optional<Instant> positionAnchorAt,
        Optional<Source> source
) implements PlaybinPlayerPlayerStateBuilder.With {
}
