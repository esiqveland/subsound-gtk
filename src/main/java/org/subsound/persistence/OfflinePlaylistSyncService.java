package org.subsound.persistence;

import org.subsound.app.state.NetworkMonitoring;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.PlaylistSimple;
import org.subsound.persistence.database.DownloadSource;
import org.subsound.persistence.database.OfflinePlaylistDao;
import org.subsound.persistence.database.OfflinePlaylistDao.OfflinePlaylist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * Keeps the songs of "available offline" playlists (and the synthetic Starred playlist) in the
 * download set. Add-only: it enqueues songs via {@link DownloadManager}, never removes them.
 *
 * <p>Modeled on {@link ServerOperationsService} (virtual-thread loop, {@link #triggerFlush()},
 * network-gated), but <b>event-driven only</b>: the loop blocks on the trigger with no timeout, so
 * there is no periodic polling. It is woken on toggle-on, on OFFLINE&rarr;ONLINE reconnect, and on
 * each playlist-list refresh.
 *
 * <p>Per-playlist watermarks skip unchanged playlists: a NORMAL playlist is re-pulled only when its
 * {@link PlaylistSimple#changedAt()} advances; Starred (which has no whole-list timestamp) uses the
 * maximum per-song starred instant. A NULL watermark (set when the user enables a playlist) forces
 * a full resync.
 */
public class OfflinePlaylistSyncService {
    private static final Logger log = LoggerFactory.getLogger(OfflinePlaylistSyncService.class);

    private final OfflinePlaylistDao dao;
    private final UUID serverId;
    private final Supplier<ServerClient> clientSupplier;
    private final Supplier<NetworkMonitoring.NetworkState> statusSupplier;
    private final DownloadManager downloadManager;
    private volatile boolean running = true;
    private volatile CountDownLatch trigger = new CountDownLatch(1);

    public OfflinePlaylistSyncService(
            OfflinePlaylistDao dao,
            UUID serverId,
            Supplier<ServerClient> clientSupplier,
            Supplier<NetworkMonitoring.NetworkState> statusSupplier,
            DownloadManager downloadManager
    ) {
        this(dao, serverId, clientSupplier, statusSupplier, downloadManager, true);
    }

    // Package-private: tests construct with autoStart=false to drive processPending() deterministically.
    OfflinePlaylistSyncService(
            OfflinePlaylistDao dao,
            UUID serverId,
            Supplier<ServerClient> clientSupplier,
            Supplier<NetworkMonitoring.NetworkState> statusSupplier,
            DownloadManager downloadManager,
            boolean autoStart
    ) {
        this.dao = dao;
        this.serverId = serverId;
        this.clientSupplier = clientSupplier;
        this.statusSupplier = statusSupplier;
        this.downloadManager = downloadManager;
        if (autoStart) {
            startProcessor();
        }
    }

    private void startProcessor() {
        Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    // Block until triggered — event-driven, no periodic poll.
                    trigger.await();
                    trigger = new CountDownLatch(1);
                    if (!running) {
                        break;
                    }
                    processPending();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in offline playlist sync processor", e);
                }
            }
        });
    }

    /** Wake the processor to (re)sync offline playlists (e.g. on toggle, reconnect, refresh). */
    public void triggerFlush() {
        trigger.countDown();
    }

    // package-private for tests
    void processPending() {
        var client = clientSupplier.get();
        if (client == null) {
            return;
        }
        var status = statusSupplier.get();
        if (status.status() == NetworkMonitoring.NetworkStatus.OFFLINE) {
            log.debug("Skipping offline playlist sync: {}", status.status());
            return;
        }

        var enabled = dao.listEnabled(serverId);
        if (enabled.isEmpty()) {
            return;
        }

        // Fetch the playlist listing once for cheap changedAt comparison of NORMAL playlists.
        Map<String, PlaylistSimple> byId = null;
        for (var row : enabled) {
            if (!running) {
                return;
            }
            try {
                switch (row.kind()) {
                    case STARRED -> syncStarred(client, row);
                    case NORMAL -> {
                        if (byId == null) {
                            byId = indexPlaylists(client);
                        }
                        syncNormal(client, row, byId.get(row.playlistId()));
                    }
                    case DOWNLOADED -> { /* not a real offline target */ }
                }
            } catch (Exception e) {
                if (isNetworkError(e)) {
                    // Went offline mid-sync — stop the batch and retry on the next trigger.
                    log.warn("Network error syncing offline playlist {}, will retry later", row.playlistId());
                    return;
                }
                log.error("Failed to sync offline playlist: {}", row.playlistId(), e);
            }
        }
    }

    private static Map<String, PlaylistSimple> indexPlaylists(ServerClient client) {
        var listing = client.getPlaylists();
        var byId = new HashMap<String, PlaylistSimple>(listing.playlists().size());
        for (var p : listing.playlists()) {
            byId.put(p.id(), p);
        }
        return byId;
    }

    private void syncNormal(ServerClient client, OfflinePlaylist row, PlaylistSimple simple) {
        if (simple == null) {
            // Deleted server-side (absent from getPlaylists) — leave the row inert.
            return;
        }
        var watermark = row.watermark();
        if (watermark.isPresent() && !simple.changedAt().isAfter(watermark.get())) {
            // Unchanged since last sync — no getPlaylist round-trip.
            return;
        }
        var full = client.getPlaylist(row.playlistId());
        for (var song : full.songs()) {
            downloadManager.enqueue(song, DownloadSource.PLAYLIST_SYNC);
        }
        dao.updateWatermark(serverId, row.playlistId(), simple.changedAt(), Instant.now());
    }

    private void syncStarred(ServerClient client, OfflinePlaylist row) {
        var songs = client.getStarred().songs();
        if (songs.isEmpty()) {
            return;
        }
        var watermark = row.watermark();
        Instant maxStarred = null;
        for (var song : songs) {
            var starred = song.starred();
            if (starred.isEmpty()) {
                // No timestamp: enqueue only on a full (first) sync so we never miss it.
                if (watermark.isEmpty()) {
                    downloadManager.enqueue(song, DownloadSource.PLAYLIST_SYNC);
                }
                continue;
            }
            var starredAt = starred.get();
            if (maxStarred == null || starredAt.isAfter(maxStarred)) {
                maxStarred = starredAt;
            }
            // First sync (no watermark): enqueue all. Otherwise only songs at/after the watermark
            // (>= so same-instant siblings are not skipped; enqueue is idempotent).
            if (watermark.isEmpty() || !starredAt.isBefore(watermark.get())) {
                downloadManager.enqueue(song, DownloadSource.PLAYLIST_SYNC);
            }
        }
        if (maxStarred != null) {
            dao.updateWatermark(serverId, row.playlistId(), maxStarred, Instant.now());
        }
    }

    private static boolean isNetworkError(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof IOException || cause instanceof UnknownHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    public void stop() {
        running = false;
        trigger.countDown();
    }
}
