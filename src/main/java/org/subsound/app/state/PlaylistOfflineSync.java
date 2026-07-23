package org.subsound.app.state;

import java.time.Instant;
import java.util.Optional;

/**
 * Our local "available offline" overlay for a playlist — sync bookkeeping that has no equivalent
 * in the server's {@link org.subsound.integration.ServerClient.PlaylistSimple}. Sourced from the
 * {@code offline_playlists} table (see {@link org.subsound.persistence.database.OfflinePlaylistDao}).
 *
 * <p>The mere presence of this record (see {@link PlaylistWithOffline#offlineSync()}) means the
 * playlist is marked offline. {@code watermark} is the last-synced high-water mark — a NORMAL
 * playlist's {@code changedAt} or the max starred instant for Starred — empty until the first
 * successful sync.
 */
public record PlaylistOfflineSync(
        Optional<Instant> watermark,
        Instant markedOfflineAt,
        Instant lastUpdatedAt
) {}
