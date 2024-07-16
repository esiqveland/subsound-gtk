package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import org.gnome.gtk.*;

public class PlayerBar extends Box {
    private final PlaybinPlayer player;
    private final ActionBar mainBar;
    private final Image albumArt;
    private final Label songTitle;
    private final Label albumTitle;
    private final Label artistTitle;

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

        var volumeButton = VolumeButton.builder().setValue(player.getVolume()).build();
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
    }
}
