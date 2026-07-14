package org.subsound.ui.views;

import org.gnome.glib.GLib;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.AppManager.AppState;
import org.subsound.app.state.AppManager.NowPlaying;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.LyricsLinesView;
import org.subsound.utils.Utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.subsound.app.state.AppManager.NowPlaying.State.LOADING;

/**
 * Fullscreen-ish lyrics view pushed onto the NavigationView. Self-contained:
 * listens to AppManager state while mapped and drives its own position tick,
 * so it can be created fresh per navigation like the other routed pages.
 */
public class LyricsPage extends Box implements AppManager.StateListener {
    private final AppManager appManager;
    private final LyricsLinesView lines;

    // Cached for the tick callback; written from onStateChanged (virtual thread).
    private volatile Duration duration = Duration.ZERO;
    private volatile Optional<Instant> positionAnchorAt = Optional.empty();
    private volatile boolean playing = false;
    // Set to true while the GLib timeout should keep running. Flipped off in onUnmap
    // so the next callback invocation returns SOURCE_REMOVE and GLib frees the source.
    private final AtomicBoolean tickActive = new AtomicBoolean(false);

    public LyricsPage(AppManager appManager) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.lines = new LyricsLinesView(
                song -> appManager.useClient(client -> client.getSongLyrics(song.id())),
                appManager::seekTo,
                new LyricsLinesView.Config(
                        Classes.lyricsLinePage,
                        -1,
                        -1,
                        -1,
                        -1,
                        -1,
                        false,
                        true
                )
        );
        this.append(lines);

        this.onMap(() -> {
            this.appManager.addOnStateChanged(this);
            this.onStateChanged(this.appManager.getState());
            this.lines.loadForCurrentSong();
            if (this.tickActive.compareAndSet(false, true)) {
                GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 300, () -> {
                    if (!this.tickActive.get()) {
                        return GLib.SOURCE_REMOVE;
                    }
                    this.onTick();
                    return GLib.SOURCE_CONTINUE;
                });
            }
        });
        this.onUnmap(() -> {
            this.appManager.removeOnStateChanged(this);
            this.tickActive.set(false);
        });
    }

    @Override
    public void onStateChanged(AppState state) {
        var nowPlayingState = state.nowPlaying().map(NowPlaying::state).orElse(LOADING);
        Optional<Duration> duration = switch (nowPlayingState) {
            case LOADING -> state.nowPlaying().map(NowPlaying::song).map(SongInfo::duration);
            case READY -> state.player().source().flatMap(s -> s.duration())
                    .or(() -> state.nowPlaying().map(NowPlaying::song).map(SongInfo::duration));
        };
        Duration position = switch (nowPlayingState) {
            case LOADING -> Duration.ZERO;
            case READY -> state.player().source().flatMap(s -> s.position()).orElse(Duration.ZERO);
        };
        this.duration = duration.orElse(Duration.ZERO);
        this.positionAnchorAt = state.player().positionAnchorAt();
        this.playing = state.player().state().isPlaying();

        this.lines.setNowPlaying(state.nowPlaying().map(NowPlaying::song));
        // While PLAYING the tick extrapolates position locally; snap once here so
        // non-PLAYING states (pause, seek, load) show the authoritative position.
        Utils.runOnMainThread(() -> this.lines.updatePosition(position));
    }

    private void onTick() {
        if (!this.playing) {
            return;
        }
        var anchor = this.positionAnchorAt;
        if (anchor.isEmpty()) {
            return;
        }
        Duration duration = this.duration;
        Duration pos = Duration.between(anchor.get(), Instant.now());
        if (pos.isNegative()) {
            pos = Duration.ZERO;
        }
        if (!duration.isZero() && pos.compareTo(duration) > 0) {
            pos = duration;
        }
        this.lines.updatePosition(pos);
    }
}
