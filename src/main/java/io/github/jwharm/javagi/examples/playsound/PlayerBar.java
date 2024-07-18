package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import org.gnome.gtk.ActionBar;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.VolumeButton;

import java.util.concurrent.atomic.AtomicBoolean;

public class PlayerBar extends Box implements PlaybinPlayer.OnStateChanged, AutoCloseable {
    private final PlaybinPlayer player;
    private final ActionBar mainBar;
    private final Image albumArt;
    private final Label songTitle;
    private final Label albumTitle;
    private final Label artistTitle;
    private final VolumeButton volumeButton;
    private final AtomicBoolean isStateChanging = new AtomicBoolean(false);

    public PlayerBar(PlaybinPlayer player) {
        super(Orientation.VERTICAL, 5);
        this.player = player;

        Box songInfo = new Box(Orientation.VERTICAL, 2);
        songTitle = Label.builder().setLabel("Song title").build();
        songInfo.append(songTitle);
        albumTitle = Label.builder().setLabel("Album title").build();
        songInfo.append(albumTitle);
        artistTitle = Label.builder().setLabel("Artist title").build();
        songInfo.append(artistTitle);

        albumArt = Image.fromIconName("playlist-symbolic");

        Box nowPlaying = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .build();
        nowPlaying.append(albumArt);
        nowPlaying.append(songInfo);

        volumeButton = VolumeButton.builder().setValue(player.getVolume()).build();
        volumeButton.onValueChanged(val -> {
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

        player.onStateChanged(this);
    }

    @Override
    public void onState(PlaybinPlayer.PlayerState next) {
        try {
            isStateChanging.set(true);
            double volume = next.volume();
            // TODO: check value within epsilon 0.01
            if (volume != volumeButton.getValue()) {
                volumeButton.setValue(next.volume());
            }
        } finally {
            isStateChanging.set(false);
        }
    }

    @Override
    public void close() throws Exception {
        player.removeOnStateChanged(this);
    }
}
