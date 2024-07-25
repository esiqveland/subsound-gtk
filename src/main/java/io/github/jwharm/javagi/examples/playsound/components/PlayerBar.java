package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.app.state.AppManager.AppState;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.jwharm.javagi.examples.playsound.components.PlayerBar.CoverArtDiff.CHANGED;
import static io.github.jwharm.javagi.examples.playsound.components.PlayerBar.CoverArtDiff.SAME;
import static io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;

public class PlayerBar extends Box implements AppManager.StateListener, AutoCloseable {
    private final ThumbLoader thumbLoader;
    private final AppManager player;
    private final ActionBar mainBar;
    private final Box albumArtBox;
    private final Label songTitle;
    private final Label albumTitle;
    private final Label artistTitle;
    private final VolumeButton volumeButton;
    private final AtomicBoolean isStateChanging = new AtomicBoolean(false);
    private final Box placeholderAlbumArt;
    private Optional<CoverArt> currentCoverArt = Optional.empty();

    public PlayerBar(ThumbLoader thumbLoader, AppManager player) {
        super(Orientation.VERTICAL, 4);
        this.thumbLoader = thumbLoader;
        this.player = player;
        this.player.addOnStateChanged(this);

        Box songInfo = new Box(Orientation.VERTICAL, 2);
        songTitle = Label.builder().setLabel("Song title").build();
        songInfo.append(songTitle);
        albumTitle = Label.builder().setLabel("Album title").build();
        songInfo.append(albumTitle);
        artistTitle = Label.builder().setLabel("Artist title").build();
        songInfo.append(artistTitle);

        this.albumArtBox = Box.builder()
            .setOrientation(Orientation.VERTICAL)
            .setHexpand(true)
            .setVexpand(true)
            .build();
        placeholderAlbumArt = AlbumArt.placeholderImage();
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
        volumeBox.append(new Label("Volume"));
        volumeBox.append(volumeButton);

        mainBar = ActionBar.builder().build();
        mainBar.packStart(nowPlaying);
        mainBar.packEnd(volumeBox);
        this.append(mainBar);
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

            double volume = state.player().volume();
            Utils.runOnMainThread(() -> {
                if (!withinEpsilon(volume, volumeButton.getValue(), 0.01)) {
                    volumeButton.setValue(volume);
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

    private void replaceAlbumArt(CoverArt coverArt) {
        Utils.runOnMainThread(() -> {
            var child = albumArtBox.getFirstChild();
            if (child != null) {
                albumArtBox.remove(child);
            }
            albumArtBox.append(new AlbumArt(
                    coverArt,
                    this.thumbLoader
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
