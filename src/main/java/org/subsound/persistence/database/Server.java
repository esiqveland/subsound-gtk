package org.subsound.persistence.database;

import org.jspecify.annotations.Nullable;
import org.subsound.integration.ServerClient.HttpHeader;
import org.subsound.integration.ServerClient.ReplayGainConfig;
import org.subsound.integration.ServerClient.ServerType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Server(
        UUID id,
        boolean isPrimary,
        ServerType serverType,
        String serverUrl,
        String username,
        Instant createdAt,
        boolean tlsSkipVerify,
        @Nullable String audioFormat,
        @Nullable Integer audioBitrate,
        List<HttpHeader> customHeaders,
        ReplayGainConfig replayGainConfig
) {
}
