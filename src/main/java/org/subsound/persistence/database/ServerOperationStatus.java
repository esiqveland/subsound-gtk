package org.subsound.persistence.database;

/**
 * Lifecycle status of a queued {@link ServerOperation}.
 */
public enum ServerOperationStatus {
    /** Not yet successfully replayed to the server. */
    PENDING,
    /** Successfully replayed to the server (completed_at is set). */
    COMPLETED,
    /** Rejected by the server in a non-recoverable way; will not be retried automatically. */
    FAILED,
}
