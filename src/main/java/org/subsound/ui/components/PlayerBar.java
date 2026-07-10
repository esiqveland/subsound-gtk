package org.subsound.ui.components;

import org.gnome.adw.Clamp;
import org.gnome.glib.GLib;
import org.gnome.gtk.ActionBar;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Scale;
import org.gnome.pango.EllipsizeMode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.AppManager.AppState;
import org.subsound.app.state.AppManager.NowPlaying;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.utils.Utils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.subsound.app.state.AppManager.NowPlaying.State.LOADING;
import static org.subsound.integration.ServerClient.CoverArt;
import static org.subsound.ui.components.PlayerBar.CoverArtDiff.CHANGED;
import static org.subsound.ui.components.PlayerBar.CoverArtDiff.SAME;
import static org.subsound.utils.Utils.runOnMainThread;
import static org.subsound.utils.Utils.withinEpsilon;

import static org.subsound.i18n.I18n.tr;

public class PlayerBar extends Box implements AppManager.StateListener {
    private final Logger log = LoggerFactory.getLogger(PlayerBar.class);
    private static final int ARTWORK_SIZE = 64;
    private static final int NOW_PLAYING_TEXT_WIDTH = 280;
    private static final int MIN_SCRUBBER_WIDTH = 320;

    private final AppManager appManager;

    private final ActionBar mainBar;

    // albumArtBox wraps the area around album art
    private final Box albumArtBox;
    // album cover art widget (updated in-place)
    private final RoundedAlbumArt albumArt;
    // box that holds the song details
    private final Box songInfoBox;
    private final ClickLabel songTitle;
    private final ClickLabel artistTitle;

    // player controls
    private final Button skipBackwardButton;
    private final Button playPauseButton;
    private final Button skipForwardButton;
    private final Button shuffleModeButton;
    private final Button repeatModeButton;
    private final PlayerScrubberV2 playerScrubber;
    private final MenuButton queueButton;
    private final PlayQueuePopover queuePopover;
    private final MenuButton lyricsButton;
    private final LyricsPopover lyricsPopover;
    private final VolumeButton volumeButton;
    private final StarButton starButton;
    private final Scale volumeScale;

    private final AtomicBoolean isStateChanging = new AtomicBoolean(false);
    private final AtomicReference<AppState> currentState;

    // playing state helps control the play/pause button
    private PlayingState playingState = PlayingState.IDLE;
    private Optional<CoverArt> currentCoverArt = Optional.empty();
    private final AtomicLong updateCounter = new AtomicLong();

    // Fields read by the GTK tick callback to self-draw scrubber progress between
    // AppState updates. Written from onStateChanged (main thread via runOnMainThread).
    private volatile Duration scrubberDuration = Duration.ZERO;
    private volatile Optional<Instant> positionAnchorAt = Optional.empty();
    private volatile Duration scrubberPosition = Duration.ZERO;
    private volatile boolean scrubberPlaying = false;
    // Set to true while the GLib timeout driving redraws should keep running. Flipped off in
    // onUnmap so the next callback invocation returns SOURCE_REMOVE and GLib frees the source.
    private final AtomicBoolean tickActive = new AtomicBoolean(false);

    enum PlayingState {
        IDLE,
        PLAYING,
        PAUSED,
        //BUFFERING,
    }

    public PlayerBar(AppManager appManager) {
        super(Orientation.VERTICAL, 2);
        this.appManager = appManager;
        this.onMap(() -> {
            this.appManager.addOnStateChanged(this);
            if (this.tickActive.compareAndSet(false, true)) {
                // 100 ms is cheap and gives visibly smooth progress for the millisecond-precision
                // scrubber paintable, while keeping us well below per-frame refresh rates. GLib
                // manages the source; it's freed when the callback returns SOURCE_REMOVE.
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
        this.currentState = new AtomicReference<>(this.appManager.getState());

        this.starButton = new StarButton(
                Optional.empty(),
                isStarredPtr -> {
                    boolean isStarred = isStarredPtr;
                    var currentState = this.currentState.get();
                    var action = currentState.nowPlaying()
                            .map(NowPlaying::song)
                            .map(songInfo -> isStarred
                                    ? new PlayerAction.Star(songInfo)
                                    : new PlayerAction.Unstar(songInfo)
                            );

                    return action
                            .map(this.appManager::handleAction)
                            .orElseGet(() -> CompletableFuture.failedFuture(new RuntimeException("no song playing")));
                }
        );
        this.starButton.setSensitive(false);
        // songInfoBox is the fixed-width slot reserved for the now-playing text + star.
        // Keeping its width constant prevents long titles from pushing the centered scrubber.
        // Internally, a vertical text stack sits next to a vcentered star button so the star
        // stays close to the text regardless of title length.
        songInfoBox = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setSpacing(6)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .setVexpand(true)
                .setHexpand(false)
                .setWidthRequest(NOW_PLAYING_TEXT_WIDTH)
                .setMarginStart(8)
                .build();

        //songTitle = Label.builder().setLabel("Song title").setHalign(Align.START).setMaxWidthChars(30).setEllipsize(EllipsizeMode.END).setCssClasses(cssClasses("heading")).build();
        songTitle = new ClickLabel("Song title", () -> {
            var state = this.currentState.get();
            if (state == null) {
                return;
            }
            state.nowPlaying().ifPresent(np -> {
                this.appManager.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(np.song().albumId()));
            });
        });
        songTitle.setHalign(Align.START);
        songTitle.setXalign(0.0f);
        songTitle.setHexpand(false);
        songTitle.setMaxWidthChars(30);
        songTitle.setEllipsize(EllipsizeMode.MIDDLE);
        songTitle.addCssClass(Classes.heading.className());

        artistTitle = new ClickLabel("Artist title", () -> {
            var state = this.currentState.get();
            if (state == null) {
                return;
            }
            state.nowPlaying().ifPresent(np -> {
                this.appManager.navigateTo(new AppNavigation.AppRoute.RouteArtistInfo(np.song().artistId()));
            });
        });
        artistTitle.setHalign(Align.START);
        artistTitle.setXalign(0.0f);
        artistTitle.setHexpand(false);
        artistTitle.setMaxWidthChars(25);
        artistTitle.setEllipsize(EllipsizeMode.MIDDLE);
        artistTitle.addCssClass(Classes.labelDim.className());
        var textStack = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(2)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .build();
        textStack.append(songTitle);
        textStack.append(artistTitle);
        this.starButton.setValign(Align.CENTER);
        songInfoBox.append(textStack);
        songInfoBox.append(this.starButton);

        this.albumArtBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHexpand(false)
                .setVexpand(true)
                .setMarginStart(4)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .build();
        this.albumArt = new RoundedAlbumArt(Optional.empty(), appManager, ARTWORK_SIZE);
        this.albumArtBox.append(this.albumArt);

        Box nowPlaying = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setHexpand(false)
                .build();
        nowPlaying.append(albumArtBox);
        // Clamp is the only Adwaita widget that enforces a hard *max* allocation on its child.
        // Setting maximumSize == tighteningThreshold makes it behave as a strict max-width box
        // (no scaling band), so labels ellipsize instead of widening the slot.
        var songInfoClamp = Clamp.builder()
                .setMaximumSize(NOW_PLAYING_TEXT_WIDTH)
                .setTighteningThreshold(NOW_PLAYING_TEXT_WIDTH)
                .setHexpand(false)
                .setHalign(Align.START)
                .setChild(songInfoBox)
                .build();
        nowPlaying.append(songInfoClamp);

        volumeButton = new VolumeButton(this.currentState.get().player().muted(), PlaybinPlayer.toVolumeCubic(this.currentState.get().player().volume()));
        volumeButton.onClicked(() -> {
            if (this.isStateChanging.get()) {
                return;
            }
            if (this.currentState.get().player().muted()) {
                this.appManager.unMute();
            } else {
                this.appManager.mute();
            }
        });

        volumeScale = Scale.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setWidthRequest(100)
                .build();
        volumeScale.setRange(0.0, 1.0);
        volumeScale.setValue(PlaybinPlayer.toVolumeCubic(currentState.get().player().volume()));
        volumeScale.setShowFillLevel(false);
        volumeScale.onValueChanged(() -> {
            if (this.isStateChanging.get()) {
                return;
            }
            var cubicVolume = this.volumeScale.getValue();
            log.info("volumeScale.onValueChanged: {}", cubicVolume);
            this.appManager.setVolume(cubicVolume);
            this.volumeButton.setVolume(cubicVolume);
        });
        volumeScale.setIncrements(0.017, 0.23);

        // Queue button with popover
        queuePopover = new PlayQueuePopover(
                this.appManager,
                index -> this.appManager.handleAction(new PlayerAction.PlayPositionInQueue(index))
        );

        queueButton = MenuButton.builder()
                .setIconName(Icons.Playlists.getIconName())
                .setTooltipText(tr("Play Queue"))
                .setPopover(queuePopover)
                .setDirection(org.gnome.gtk.ArrowType.UP)
                .build();

        // Lyrics button with popover
        lyricsPopover = new LyricsPopover(
                song -> this.appManager.useClient(client -> client.getSongLyrics(song.id())),
                this.appManager::seekTo
        );
        lyricsButton = MenuButton.builder()
                .setIconName(Icons.Microphone.getIconName())
                .setTooltipText(tr("Lyrics"))
                .setPopover(lyricsPopover)
                .setDirection(org.gnome.gtk.ArrowType.UP)
                .build();

        var volumeBox = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .build();
        volumeBox.append(lyricsButton);
        volumeBox.append(queueButton);
        volumeBox.append(volumeButton);
        volumeBox.append(volumeScale);

        skipBackwardButton = Button.builder().setIconName(Icons.SkipBackward.getIconName()).build();
        skipBackwardButton.addCssClass("circular");
        skipBackwardButton.onClicked(this::onPrev);
        skipForwardButton = Button.builder().setIconName(Icons.SkipForward.getIconName()).build();
        skipForwardButton.addCssClass("circular");
        skipForwardButton.onClicked(this::onNext);
        shuffleModeButton = Button.builder().setIconName(Icons.PlaylistShuffle.getIconName()).build();
        shuffleModeButton.addCssClass("circular");
        shuffleModeButton.setTooltipText(tr("Shuffle"));
        shuffleModeButton.onClicked(() -> {
            AppState state = this.appManager.getState();
            var currentMode = state.queue().playMode();
            var mode = switch (currentMode) {
                case SHUFFLE -> PlayerAction.PlayMode.NORMAL;
                case NORMAL, REPEAT_ONE -> PlayerAction.PlayMode.SHUFFLE;
            };
            // optimistically update the UI:
            this.updatePlayMode(mode);
            this.appManager.handleAction(new PlayerAction.SetPlayMode(mode));
        });

        repeatModeButton = Button.builder().setIconName(Icons.PlaylistRepeatSong.getIconName()).build();
        repeatModeButton.addCssClass("circular");
        repeatModeButton.setTooltipText(tr("Repeat song"));
        repeatModeButton.onClicked(() -> {
            AppState state = this.appManager.getState();
            var currentMode = state.queue().playMode();
            var mode = currentMode == PlayerAction.PlayMode.REPEAT_ONE
                    ? PlayerAction.PlayMode.NORMAL
                    : PlayerAction.PlayMode.REPEAT_ONE;
            // optimistically update the UI:
            this.updatePlayMode(mode);
            this.appManager.handleAction(new PlayerAction.SetPlayMode(mode));
        });
        updatePlayMode(appManager.getState().queue().playMode());

        playPauseButton = Button.builder().setIconName(Icons.PLAY.getIconName()).build();
        playPauseButton.addCssClass("circular");
        playPauseButton.addCssClass("playerBar-playPauseButton");
        playPauseButton.setSizeRequest(48, 48);
        playPauseButton.onClicked(this::playPause);
        updatePlayingState(toPlayingState(appManager.getState().player().state()));
        playerScrubber = new PlayerScrubberV2(this.appManager::seekTo);
        playerScrubber.setSizeRequest(MIN_SCRUBBER_WIDTH, -1);
        playerScrubber.setHexpand(false);
        playerScrubber.setHalign(Align.CENTER);

        var playerControls = Box.builder()
                .setSpacing(2)
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .build();
        playerControls.append(shuffleModeButton);
        playerControls.append(skipBackwardButton);
        playerControls.append(playPauseButton);
        playerControls.append(skipForwardButton);
        playerControls.append(repeatModeButton);

        Box centerWidget = Box.builder().setOrientation(Orientation.VERTICAL).setSpacing(2).setHexpand(true).build();
        centerWidget.append(playerControls);
        centerWidget.append(playerScrubber);

        mainBar = ActionBar.builder().setVexpand(true).setValign(Align.CENTER).build();
        mainBar.packStart(nowPlaying);
        mainBar.setCenterWidget(centerWidget);
        mainBar.packEnd(volumeBox);
        this.append(mainBar);
        this.addCssClass("playerbar");

        // make sure we update if a state with something playing was already loaded at start:
        var startState = this.currentState.get();
        if (startState.nowPlaying().isPresent()) {
            this.onStateChanged(startState.withNowPlaying(Optional.empty()), startState);
        }
    }

    private void onPrev() {
        this.appManager.prev();
    }

    private void onNext() {
        this.appManager.next();
    }

    private void playPause() {
        switch (playingState) {
            case IDLE -> this.appManager.play();
            case PAUSED -> this.appManager.play();
            case PLAYING -> this.appManager.pause();
        }
    }

    @Override
    public void onStateChanged(AppState state) {
        var prevState = this.currentState.get();
        onStateChanged(prevState, state);
    }

    public void onStateChanged(@Nullable AppState prevState, AppState state) {
        var player = state.player();
        var playing = state.nowPlaying();
        var queue = state.queue();

        Optional<CoverArt> nextCover = playing.flatMap(st -> st.song().coverArt());
        try {
            isStateChanging.set(true);

            var diffResult = diffCover(currentCoverArt, nextCover);
            switch (diffResult) {
                case SAME -> {
                    // do nothing
                }
                case CHANGED -> {
                    this.currentCoverArt = nextCover;
                    nextCover.ifPresentOrElse(
                            this::replaceAlbumArt,
                            this::placeholderCover
                    );
                }
            }

            var playMode = queue.playMode();
            var prevPlayMode = prevState.queue().playMode();
            if (playMode != prevPlayMode) {
                this.updatePlayMode(playMode);
            }

            PlayingState nextPlayingState = toPlayingState(player.state());
            double linearVolume = state.player().volume();

            var nowPlayingState = state.nowPlaying().map(NowPlaying::state).orElse(LOADING);
            Optional<Duration> duration = switch (nowPlayingState) {
                // we get the duration from the songInfo while LOADING:
                case LOADING -> state.nowPlaying().map(NowPlaying::song).map(SongInfo::duration);
                // READY: get the duration from the loaded source:
                case READY -> state.player().source().flatMap(s -> s.duration())
                        .or(() -> state.nowPlaying().map(NowPlaying::song).map(SongInfo::duration));
            };
            Duration position = switch (nowPlayingState) {
                // we get the position ZERO while loading:
                case LOADING -> Duration.ZERO;
                // READY: get the position from the loaded source:
                case READY -> state.player().source().flatMap(s -> s.position()).orElse(Duration.ZERO);
            };
            Duration effectiveDuration = duration.orElse(Duration.ZERO);
            this.playerScrubber.updateDuration(effectiveDuration);

            // Cache state for the tick callback. While PLAYING the tick extrapolates position
            // locally from positionAnchorAt, so we avoid pushing every position update through
            // the scrubber here. For non-PLAYING states (LOADING/PAUSED/READY/EOS) the tick is
            // inactive and we snap the scrubber to the authoritative position once.
            this.scrubberDuration = effectiveDuration;
            this.scrubberPosition = position;
            this.positionAnchorAt = state.player().positionAnchorAt();
            boolean wasPlaying = this.scrubberPlaying;
            boolean isPlayingNow = nextPlayingState == PlayingState.PLAYING;
            this.scrubberPlaying = isPlayingNow;
            // onStateChanged runs on an AppManager listener virtual thread, so GTK writes via
            // updatePosition must be marshalled onto the main thread here. (onTick runs on the
            // main thread already and calls updatePosition directly.)
            if (!isPlayingNow) {
                // On PLAYING → non-PLAYING the last extrapolated tick may be a few ms ahead of
                // the real pause point; snap the scrubber back to the authoritative position.
                // Also covers LOADING/EOS where we want a definite frame.
                runOnMainThread(() -> {
                    this.playerScrubber.updatePosition(position);
                    this.lyricsPopover.updatePosition(position);
                });
            } else if (!wasPlaying) {
                // non-PLAYING → PLAYING: seed the scrubber at `position` immediately; the tick
                // takes over from the next frame.
                runOnMainThread(() -> {
                    this.playerScrubber.updatePosition(position);
                    this.lyricsPopover.updatePosition(position);
                });
            }

            this.lyricsPopover.setNowPlaying(playing.map(NowPlaying::song));

            Optional<SongInfo> prevSongInfo = prevState.nowPlaying().map(NowPlaying::song);
            var prevSongTitle = prevSongInfo.map(SongInfo::title).orElse("");
            var prevSongArtist = prevSongInfo.map(SongInfo::artistName).orElse("");
            var prevSongAlbumTitle = prevSongInfo.map(SongInfo::album).orElse("");
            var prevSongStarred = prevSongInfo.flatMap(SongInfo::starred);

            Utils.runOnMainThread(() -> {
                this.volumeButton.setMute(player.muted());
                var cubicVolume = PlaybinPlayer.toVolumeCubic(linearVolume);
                if (!withinEpsilon(cubicVolume, volumeScale.getValue(), 0.01)) {
                    log.info("volume is outdated: %.2f".formatted(cubicVolume));
                    this.volumeButton.setVolume(cubicVolume);
                    this.volumeScale.setValue(cubicVolume);
                }
                if (nextPlayingState != playingState) {
                    this.updatePlayingState(nextPlayingState);
                }

                if (playing.isPresent() != prevSongInfo.isPresent()) {
                    this.starButton.setSensitive(playing.isPresent());
                }

                playing.ifPresent(nowPlaying -> {
                    var song = nowPlaying.song();
                    if (prevSongStarred != song.starred()) {
                        this.starButton.setStarredAt(song.starred());
                    }
                    this.starButton.setSensitive(true);

                    if (!song.title().equals(prevSongTitle)) {
                        songTitle.setLabel(song.title());
                    }
                    if (!song.artistName().equals(prevSongArtist)) {
                        artistTitle.setLabel(song.artistName());
                    }
                    switch (nowPlaying.state()) {
                        case LOADING -> this.playerScrubber.setFill(nowPlaying.bufferingProgress().total(), nowPlaying.bufferingProgress().count());
                        case READY -> this.playerScrubber.disableFill();
                    }
                });
            });
        } finally {
            isStateChanging.set(false);
            currentState.set(state);
        }
    }

    private void onTick() {
        if (!this.scrubberPlaying) {
            return;
        }
        var anchor = this.positionAnchorAt;
        if (anchor.isEmpty()) {
            return;
        }
        Duration duration = this.scrubberDuration;
        Duration pos = Duration.between(anchor.get(), Instant.now());
        if (pos.isNegative()) {
            pos = Duration.ZERO;
        }
        if (!duration.isZero() && pos.compareTo(duration) > 0) {
            pos = duration;
        }
        // Runs on GTK main thread, so updatePosition's runOnMainThread is a no-op hop.
        this.playerScrubber.updatePosition(pos);
        this.lyricsPopover.updatePosition(pos);
    }

    private void updatePlayMode(PlayerAction.PlayMode playMode) {
        runOnMainThread(() -> {
            // Update shuffle button
            if (playMode == PlayerAction.PlayMode.SHUFFLE) {
                this.shuffleModeButton.addCssClass(Classes.colorAccent.className());
            } else {
                this.shuffleModeButton.removeCssClass(Classes.colorAccent.className());
            }
            // Update repeat button
            if (playMode == PlayerAction.PlayMode.REPEAT_ONE) {
                this.repeatModeButton.addCssClass(Classes.colorAccent.className());
            } else {
                this.repeatModeButton.removeCssClass(Classes.colorAccent.className());
            }
        });
    }

    private void updatePlayingState(PlayingState nextPlayingState) {
        this.playingState = nextPlayingState;
        switch (nextPlayingState) {
            case IDLE, PAUSED -> this.playPauseButton.setIconName("media-playback-start-symbolic");
            case PLAYING -> this.playPauseButton.setIconName("media-playback-pause-symbolic");
        }
    }

    private static PlayingState toPlayingState(PlaybinPlayer.PlayerStates state) {
        return switch (state) {
            case INIT -> PlayingState.IDLE;
            case BUFFERING -> PlayingState.PLAYING;
            case READY -> PlayingState.PAUSED;
            case PAUSED -> PlayingState.PAUSED;
            case PLAYING -> PlayingState.PLAYING;
            case END_OF_STREAM -> PlayingState.PLAYING;
        };
    }

    private void replaceAlbumArt(CoverArt coverArt) {
        Utils.runOnMainThread(() -> this.albumArt.update(Optional.of(coverArt)));
    }

    private void placeholderCover() {
        Utils.runOnMainThread(() -> this.albumArt.update(Optional.empty()));
    }

    enum CoverArtDiff {
        SAME,
        CHANGED,
    }

    private CoverArtDiff diffCover(Optional<CoverArt> currentCoverArt, Optional<CoverArt> coverArt) {
        if (currentCoverArt.isEmpty() && coverArt.isEmpty()) {
            return SAME;
        }
        if (currentCoverArt.isEmpty() && coverArt.isPresent()) {
            return CHANGED;
        }
        if (currentCoverArt.isPresent() && coverArt.isEmpty()) {
            return CHANGED;
        }
        if (currentCoverArt.isPresent() && coverArt.isPresent()) {
            if (currentCoverArt.get().coverArtId().equals(coverArt.get().coverArtId())) {
                return SAME;
            } else {
                return CHANGED;
            }
        }
        return SAME;
    }
}
