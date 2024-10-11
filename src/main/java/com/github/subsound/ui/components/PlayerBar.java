package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.AppManager.AppState;
import com.github.subsound.app.state.AppManager.NowPlaying;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.ActionBar;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Scale;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.subsound.app.state.AppManager.NowPlaying.State.LOADING;
import static com.github.subsound.integration.ServerClient.CoverArt;
import static com.github.subsound.ui.components.PlayerBar.CoverArtDiff.CHANGED;
import static com.github.subsound.ui.components.PlayerBar.CoverArtDiff.SAME;
import static com.github.subsound.utils.Utils.withinEpsilon;

public class PlayerBar extends Box implements AppManager.StateListener {
    private static final int ARTWORK_SIZE = 64;

    private final AppManager appManager;

    private final ActionBar mainBar;

    // albumArtBox wraps the area around album art
    private final Box albumArtBox;
    // box that holds the album cover photo
    private final BoxHolder<Widget> albumArtHolder;
    // box that holds the song details
    private final Box songInfoBox;
    private final ClickLabel songTitle;
    private final ClickLabel artistTitle;
    private final Widget placeholderAlbumArt;

    // player controls
    private final Button skipBackwardButton;
    private final Button playPauseButton;
    private final Button skipForwardButton;
    private final PlayerScrubber playerScrubber;
    private final VolumeButton volumeButton;
    private final StarButton starButton;
    private final Scale volumeScale;

    private final AtomicBoolean isStateChanging = new AtomicBoolean(false);
    private final AtomicReference<AppState> currentState;

    // playing state helps control the play/pause button
    private PlayingState playingState = PlayingState.IDLE;
    private Optional<CoverArt> currentCoverArt = Optional.empty();

    enum PlayingState {
        IDLE,
        PLAYING,
        PAUSED,
        //BUFFERING,
    }

    public PlayerBar(AppManager appManager) {
        super(Orientation.VERTICAL, 2);
        this.appManager = appManager;
        this.onMap(() -> this.appManager.addOnStateChanged(this));
        this.onUnmap(() -> this.appManager.removeOnStateChanged(this));
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
        songInfoBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(2)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .setVexpand(true)
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
        songTitle.setMaxWidthChars(30);
        songTitle.setEllipsize(EllipsizeMode.END);
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
        artistTitle.setMaxWidthChars(28);
        artistTitle.setEllipsize(EllipsizeMode.END);
        artistTitle.addCssClass(Classes.labelDim.className());
        songInfoBox.append(songTitle);
        songInfoBox.append(artistTitle);

        this.albumArtBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHexpand(false)
                .setVexpand(true)
                .setMarginStart(4)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .build();
        this.albumArtHolder = new BoxHolder<>();
        this.placeholderAlbumArt = RoundedAlbumArt.placeholderImage(ARTWORK_SIZE);
        this.albumArtBox.append(this.albumArtHolder);
        this.albumArtHolder.setChild(placeholderAlbumArt);

        Box nowPlaying = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .build();
        nowPlaying.append(albumArtBox);
        nowPlaying.append(songInfoBox);
        var starredButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(Align.CENTER).build();
        starredButtonBox.append(this.starButton);
        nowPlaying.append(starredButtonBox);

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
            System.out.println("volumeScale.onValueChanged: " + cubicVolume);
            this.appManager.setVolume(cubicVolume);
            this.volumeButton.setVolume(cubicVolume);
        });
        volumeScale.setIncrements(0.017, 0.23);

        var volumeBox = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .build();
        volumeBox.append(volumeButton);
        volumeBox.append(volumeScale);

        skipBackwardButton = Button.builder().setIconName(Icons.SkipBackward.getIconName()).build();
        skipBackwardButton.addCssClass("circular");
        skipBackwardButton.onClicked(this::onPrev);
        skipForwardButton = Button.builder().setIconName(Icons.SkipForward.getIconName()).build();
        skipForwardButton.addCssClass("circular");
        skipForwardButton.onClicked(this::onNext);

        playPauseButton = Button.builder().setIconName(Icons.PLAY.getIconName()).build();
        playPauseButton.addCssClass("circular");
        playPauseButton.addCssClass("playerBar-playPauseButton");
        playPauseButton.setSizeRequest(48, 48);
        playPauseButton.onClicked(this::playPause);
        updatePlayingState(toPlayingState(appManager.getState().player().state()));
        playerScrubber = new PlayerScrubber(this.appManager::seekTo);

        var playerControls = Box.builder()
                .setSpacing(2)
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .build();
        playerControls.append(skipBackwardButton);
        playerControls.append(playPauseButton);
        playerControls.append(skipForwardButton);

        Box centerWidget = Box.builder().setOrientation(Orientation.VERTICAL).setSpacing(2).build();
        centerWidget.append(playerControls);
        centerWidget.append(playerScrubber);

        mainBar = ActionBar.builder().setVexpand(true).setValign(Align.CENTER).build();
        mainBar.packStart(nowPlaying);
        mainBar.setCenterWidget(centerWidget);
        mainBar.packEnd(volumeBox);
        this.append(mainBar);
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
        var player = state.player();
        var playing = state.nowPlaying();
        var prevState = this.currentState.get();

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
            this.playerScrubber.updateDuration(duration.orElse(Duration.ZERO));
            this.playerScrubber.updatePosition(position);

            Optional<SongInfo> prevSongInfo = prevState.nowPlaying().map(NowPlaying::song);
            var prevSongTitle = prevSongInfo.map(SongInfo::title).orElse("");
            var prevSongArtist = prevSongInfo.map(SongInfo::artist).orElse("");
            var prevSongAlbumTitle = prevSongInfo.map(SongInfo::album).orElse("");
            var prevSongStarred = prevSongInfo.flatMap(SongInfo::starred);

            Utils.runOnMainThread(() -> {
                this.volumeButton.setMute(player.muted());
                var cubicVolume = PlaybinPlayer.toVolumeCubic(linearVolume);
                if (!withinEpsilon(cubicVolume, volumeScale.getValue(), 0.01)) {
                    System.out.printf("volume is outdated: %.2f%n", cubicVolume);
                    this.volumeButton.setVolume(cubicVolume);
                }
                if (!withinEpsilon(cubicVolume, volumeScale.getValue(), 0.01)) {
                    volumeScale.setValue(cubicVolume);
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
                    if (!song.artist().equals(prevSongArtist)) {
                        artistTitle.setLabel(song.artist());
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
        Utils.runOnMainThread(() -> this.albumArtHolder.setChild(new RoundedAlbumArt(
                coverArt,
                this.appManager.getThumbnailCache(),
                ARTWORK_SIZE
        )));
    }

    private void placeholderCover() {
        Utils.runOnMainThread(() -> {
            var child = albumArtBox.getFirstChild();
            if (child != null) {
                albumArtBox.remove(child);
            }
            albumArtBox.append(placeholderAlbumArt);
        });
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
