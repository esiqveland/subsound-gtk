package org.subsound.persistence.database;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A row in the {@code server_operations_queue}: a server-side operation recorded (typically while
 * offline) to be replayed to the server later. Rows are replayed in {@code id} order.
 *
 * @param executedAt  the last time the operation was run against the server (any attempt)
 * @param completedAt when the operation succeeded; non-empty iff {@code status} is COMPLETED
 */
public record ServerOperation(
        long id,
        UUID serverId,
        ServerOperationType type,
        ServerOperationPayload payload,
        ServerOperationStatus status,
        Instant createdAt,
        Optional<Instant> executedAt,
        Optional<Instant> completedAt
) {}
