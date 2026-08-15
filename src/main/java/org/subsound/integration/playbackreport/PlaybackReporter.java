package org.subsound.integration.playbackreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ReportNowPlaying;
import org.subsound.integration.ServerClient.ReportNowPlaying.PlayerState;
import org.subsound.sound.PlayerStates;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class PlaybackReporter implements AppManager.StateListener, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlaybackReporter.class);
    private final Supplier<ServerClient> clientSupplier;
    private final AtomicReference<ReportNowPlaying> lastReport = new AtomicReference<>();
    // Single-threaded so reports are submitted to the server in some order and never overlap (bounded by 1)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("playback-reporter-", 0).factory()
    );

    public PlaybackReporter(Supplier<ServerClient> clientSupplier) {
        this.clientSupplier = clientSupplier;
    }

    public CompletableFuture<ReportNowPlaying> reportNowPlayingAsync(ReportNowPlaying report) {
        return CompletableFuture.supplyAsync(() -> {
            var client = clientSupplier.get();
            // during startup, or first onboarding, client may not be ready yet, but since AppState keeps changing,
            // we get notified in PlaybackReporter.onStateChanged()
            if (client == null) {
                return report;
            }
            client.nowPlaying(report);
            log.info("PlaybackReporter.reportNowPlayingAsync: reported: {}", report);
            return report;
        }, executor).handleAsync((ok, throwable) -> {
            if (throwable != null) {
                if (throwable instanceof InterruptedException || throwable.getCause() instanceof InterruptedException) {
                    log.info("PlaybackReporter.reportNowPlayingAsync: interrupted: {}", report);
                    return ok;
                }
                // intentionally do NOT reset lastReport: under fast-fail (server down),
                // resetting causes a 4 req/s cascade as every 250ms position tick re-submits.
                // keeping lastReport means equal ticks stay deduped; the next genuine state
                // change (pause/resume/song change) will retry naturally.
                log.warn("PlaybackReporter.reportNowPlayingAsync: failed to report: {}", report, throwable);
            }
            return ok;
        }, executor);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        ReportNowPlaying report = getReport(state);
        var prev = lastReport.getAndSet(report);
        if (prev != null && prev.equals(report)) {
            return;
        }
        reportNowPlayingAsync(report);
    }

    private static ReportNowPlaying getReport(AppManager.AppState state) {
        var nowPlaying = state.nowPlaying().orElse(null);
        var songId = nowPlaying == null ? Optional.<String>empty() : Optional.ofNullable(nowPlaying.song().id());
        var player = state.player();
        var pos = state.player().source().flatMap(s -> s.position()).orElse(Duration.ZERO);
        return new ReportNowPlaying(
                songId,
                player.playbackStartedAt(),
                player.positionAnchorAt(),
                pos.toMillis(),
                toPlaybackState(player.state())
        );
    }

    private static PlayerState toPlaybackState(PlayerStates state) {
        return switch (state) {
            case PLAYING, BUFFERING -> PlayerState.PLAYING;
            case READY, INIT, END_OF_STREAM -> PlayerState.STOPPED;
            case PAUSED -> PlayerState.PAUSED;
        };
    }
}
