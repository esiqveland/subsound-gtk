package org.subsound.persistence.database;

/**
 * Payload of a queued {@link ServerOperation}. The concrete record is chosen by the row's
 * {@link ServerOperationType} (the discriminator stored in the operation_type column), so the
 * payload itself is serialized as a plain JSON object without a type tag.
 */
public sealed interface ServerOperationPayload {
    record StarSong(String songId) implements ServerOperationPayload {}

    record UnstarSong(String songId) implements ServerOperationPayload {}
}
