package org.subsound.app.state;

import org.subsound.integration.ServerClient.PlaylistSimple;

import java.util.Optional;

/**
 * A server {@link PlaylistSimple} enriched with our local offline-sync overlay. Keeps the immutable
 * server DTO intact and layers {@link PlaylistOfflineSync} beside it, present iff the playlist is
 * marked "available offline".
 */
public record PlaylistWithOffline(
        PlaylistSimple playlist,
        Optional<PlaylistOfflineSync> offlineSync
) {
    public boolean isKeepOffline() {
        return offlineSync.isPresent();
    }

    public String id() {
        return playlist.id();
    }
}
