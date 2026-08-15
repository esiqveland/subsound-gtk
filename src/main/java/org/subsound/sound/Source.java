package org.subsound.sound;

import io.soabase.recordbuilder.core.RecordBuilderFull;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@RecordBuilderFull
public record Source(
        URI current,
        Optional<Duration> position,
        Optional<Duration> duration
) implements PlaybinPlayerSourceBuilder.With {
}
