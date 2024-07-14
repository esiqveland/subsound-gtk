package io.github.jwharm.javagi.examples.playsound;

import org.gnome.gtk.*;

public class PlayerBar extends Box {
    private final ActionBar mainBar;
    private final Image albumArt;
    private final Label songTitle;
    private final Label albumTitle;
    private final Label artistTitle;

    public PlayerBar() {
        super(Orientation.VERTICAL, 5);

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

        var volumeBox = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setValign(Align.CENTER)
                .build();
        volumeBox.append(new Label("Volume"));

        mainBar = ActionBar.builder().build();
        mainBar.packStart(nowPlaying);
        mainBar.packEnd(volumeBox);
        this.append(mainBar);
    }
}
