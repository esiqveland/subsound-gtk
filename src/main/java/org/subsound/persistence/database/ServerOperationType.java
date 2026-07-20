package org.subsound.persistence.database;

import org.subsound.persistence.database.ServerOperationPayload.StarSong;
import org.subsound.persistence.database.ServerOperationPayload.UnstarSong;

/**
 * Type of a queued {@link ServerOperation}. Stored verbatim in the operation_type column and used
 * as the discriminator that selects which {@link ServerOperationPayload} record the payload JSON
 * deserializes into.
 */
public enum ServerOperationType {
    STAR(StarSong.class),
    UNSTAR(UnstarSong.class);

    private final Class<? extends ServerOperationPayload> payloadClass;

    ServerOperationType(Class<? extends ServerOperationPayload> payloadClass) {
        this.payloadClass = payloadClass;
    }

    public Class<? extends ServerOperationPayload> payloadClass() {
        return payloadClass;
    }
}
