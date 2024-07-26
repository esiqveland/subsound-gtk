package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.app.state.AppManager.AppState;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.jwharm.javagi.examples.playsound.views.components.PlayerBar.CoverArtDiff.CHANGED;
import static io.github.jwharm.javagi.examples.playsound.views.components.PlayerBar.CoverArtDiff.SAME;
import static io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;

public class PlayerBar extends Box implements AppManager.StateListener, AutoCloseable {
    private static final int ARTWORK_SIZE = 64;

    private final ThumbLoader thumbLoader;
    private final AppManager player;
    private final ActionBar mainBar;
    private final Box albumArtBox;
    private final Label songTitle;
    private final Label albumTitle;
    private final Label artistTitle;
    private final VolumeButton volumeButton;
    private final Button playPauseButton;
    private final AtomicBoolean isStateChanging = new AtomicBoolean(false);
    private final Widget placeholderAlbumArt;
    private final PlayerScrubber playerScrubber;

    // playing state helps control the play/pause button
    private PlayingState playingState = PlayingState.IDLE;
    private Optional<CoverArt> currentCoverArt = Optional.empty();

    enum PlayingState {
        IDLE,
        PLAYING,
        PAUSED,
        //BUFFERING,
    }

    public PlayerBar(ThumbLoader thumbLoader, AppManager player) {
        super(Orientation.VERTICAL, 2);
        this.thumbLoader = thumbLoader;
        this.player = player;
        this.player.addOnStateChanged(this);

        Box songInfo = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(2)
                .setHalign(Align.START)
                .setValign(Align.CENTER)
                .setVexpand(true)
                .setMarginStart(8)
                .build();
        songTitle = Label.builder().setLabel("Song title").setHalign(Align.START).build();
        songInfo.append(songTitle);
        albumTitle = Label.builder().setLabel("Album title").setHalign(Align.START).build();
        songInfo.append(albumTitle);
        artistTitle = Label.builder().setLabel("Artist title").setHalign(Align.START).build();
        songInfo.append(artistTitle);

        this.albumArtBox = Box.builder()
            .setOrientation(Orientation.VERTICAL)
            .setHexpand(false)
            .setVexpand(true)
            .setMarginStart(4)
            .setHalign(Align.START)
            .setValign(Align.CENTER)
            .build();
        placeholderAlbumArt = RoundedAlbumArt.placeholderImage(ARTWORK_SIZE);
        albumArtBox.append(placeholderAlbumArt);

        Box nowPlaying = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .build();
        nowPlaying.append(albumArtBox);
        nowPlaying.append(songInfo);

        AppState state = player.getState();
        volumeButton = VolumeButton.builder().setValue(state.player().volume()).build();
        volumeButton.onValueChanged(val -> {
            if (this.isStateChanging.get()) {
                return;
            }
            System.out.println("volumeButton.onValueChanged: " + val);
            this.player.setVolume(val);
        });

        var volumeBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setValign(Align.CENTER)
                .build();
        volumeBox.append(volumeButton);

        playPauseButton = Button.withLabel("Play");
        playPauseButton.onClicked(this::playPause);
        playerScrubber = new PlayerScrubber();

        var playerControls = Box.builder()
                .setSpacing(2)
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .build();
        playerControls.append(playPauseButton);

        Box centerWidget = Box.builder().setOrientation(Orientation.VERTICAL).setSpacing(2).build();
        centerWidget.append(playerControls);
        centerWidget.append(playerScrubber);

        mainBar = ActionBar.builder().setVexpand(true).setValign(Align.CENTER).build();
        mainBar.packStart(nowPlaying);
        mainBar.setCenterWidget(centerWidget);
        mainBar.packEnd(volumeBox);
        this.append(mainBar);
    }

    private void playPause() {
        switch (playingState) {
            case IDLE -> this.player.play();
            case PAUSED -> this.player.play();
            case PLAYING -> this.player.pause();
        }
    }

    @Override
    public void close() throws Exception {
        this.player.removeOnStateChanged(this);
    }

    public static boolean withinEpsilon(double value1, double value2, double epsilon) {
        var diff = Math.abs(value1 - value2);
        return diff < epsilon;
    }

    @Override
    public void onStateChanged(AppState state) {
        var player = state.player();
        var playing = state.nowPlaying();
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
            double volume = state.player().volume();

            Optional<Duration> duration = state.player().source().flatMap(s -> s.duration());
            this.playerScrubber.updateDuration(duration.orElse(Duration.ZERO));

            Utils.runOnMainThread(() -> {
                if (!withinEpsilon(volume, volumeButton.getValue(), 0.01)) {
                    volumeButton.setValue(volume);
                }
                if (nextPlayingState != playingState) {
                    this.updatePlayingState(nextPlayingState);
                }

                playing.ifPresent(nowPlaying -> {
                    var song = nowPlaying.song();

                    if (!song.title().equals(songTitle.getLabel())) {
                        songTitle.setLabel(song.title());
                    }
                    if (!song.album().equals(albumTitle.getLabel())) {
                        albumTitle.setLabel(song.album());
                    }
                    if (!song.artist().equals(artistTitle.getLabel())) {
                        artistTitle.setLabel(song.artist());
                    }
                });
            });
        } finally {
            isStateChanging.set(false);
        }

    }

    private void updatePlayingState(PlayingState nextPlayingState) {
        this.playingState = nextPlayingState;
        switch (nextPlayingState) {
            case IDLE, PAUSED -> this.playPauseButton.setLabel("Play");
            case PLAYING -> this.playPauseButton.setLabel("Pause");
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
        Utils.runOnMainThread(() -> {
            var child = albumArtBox.getFirstChild();
            if (child != null) {
                albumArtBox.remove(child);
            }
            albumArtBox.append(new RoundedAlbumArt(
                    coverArt,
                    this.thumbLoader,
                    ARTWORK_SIZE
            ));
        });
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
