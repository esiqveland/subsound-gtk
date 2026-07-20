package org.subsound.persistence;

import org.subsound.app.state.NetworkMonitoring;
import org.subsound.integration.ServerClient;
import org.subsound.persistence.database.ServerOperation;
import org.subsound.persistence.database.ServerOperationPayload.StarSong;
import org.subsound.persistence.database.ServerOperationPayload.UnstarSong;
import org.subsound.persistence.database.ServerOperationType;
import org.subsound.persistence.database.ServerOperationsDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Replays queued server operations (star/unstar recorded while offline) once connectivity returns.
 *
 * <p>Modeled on {@link ScrobbleService}: a virtual-thread loop that polls every 60s or on demand via
 * {@link #triggerFlush()}, gated on network status. Operations are replayed in {@code id} order.
 * Because it only replays while ONLINE (calling the online {@code CachingClient}, which delegates),
 * it never re-enters the offline enqueue path.
 */
public class ServerOperationsService {
    private static final Logger log = LoggerFactory.getLogger(ServerOperationsService.class);

    private final ServerOperationsDao dao;
    private final UUID serverId;
    private final Supplier<ServerClient> clientSupplier;
    private final Supplier<NetworkMonitoring.NetworkState> statusSupplier;
    private volatile boolean running = true;
    private volatile CountDownLatch trigger = new CountDownLatch(1);

    public ServerOperationsService(
            ServerOperationsDao dao,
            UUID serverId,
            Supplier<ServerClient> clientSupplier,
            Supplier<NetworkMonitoring.NetworkState> statusSupplier
    ) {
        this(dao, serverId, clientSupplier, statusSupplier, true);
    }

    // Package-private: tests construct with autoStart=false to drive processPending() deterministically.
    ServerOperationsService(
            ServerOperationsDao dao,
            UUID serverId,
            Supplier<ServerClient> clientSupplier,
            Supplier<NetworkMonitoring.NetworkState> statusSupplier,
            boolean autoStart
    ) {
        this.dao = dao;
        this.serverId = serverId;
        this.clientSupplier = clientSupplier;
        this.statusSupplier = statusSupplier;
        if (autoStart) {
            startProcessor();
        }
    }

    private void startProcessor() {
        Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    processPending();
                    trigger.await(60, TimeUnit.SECONDS);
                    trigger = new CountDownLatch(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in server operations processor", e);
                }
            }
        });
    }

    /** Enqueue a star operation and wake the processor to replay it as soon as we are online. */
    public void enqueueStar(String songId) {
        dao.enqueue(serverId, ServerOperationType.STAR, new StarSong(songId), Instant.now());
        triggerFlush();
    }

    /** Enqueue an unstar operation and wake the processor to replay it as soon as we are online. */
    public void enqueueUnstar(String songId) {
        dao.enqueue(serverId, ServerOperationType.UNSTAR, new UnstarSong(songId), Instant.now());
        triggerFlush();
    }

    /** Wake the processor immediately (e.g. on reconnect) instead of waiting for the next poll. */
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
            log.debug("Skipping server operations replay: {}", status.status());
            return;
        }

        var pending = dao.listPending(serverId);
        if (pending.isEmpty()) {
            return;
        }

        log.info("Replaying {} pending server operations", pending.size());
        for (var op : pending) {
            if (!running) {
                return;
            }
            var attemptedAt = Instant.now();
            try {
                execute(client, op);
                dao.markCompleted(op.id(), attemptedAt, Instant.now());
                log.info("Replayed server operation: id={} type={}", op.id(), op.type());
            } catch (Exception e) {
                if (isNetworkError(e)) {
                    // We went offline mid-replay: keep the op PENDING, record the attempt, and stop
                    // the batch. The remaining ops are retried on the next reconnect/poll.
                    dao.touchExecuted(op.id(), attemptedAt);
                    log.warn("Network error replaying server operation id={}, will retry later", op.id());
                    return;
                }
                dao.markFailed(op.id(), attemptedAt);
                log.error("Server operation permanently failed: id={} type={}", op.id(), op.type(), e);
            }
        }
    }

    private void execute(ServerClient client, ServerOperation op) {
        switch (op.payload()) {
            case StarSong p -> client.starId(p.songId());
            case UnstarSong p -> client.unStarId(p.songId());
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
