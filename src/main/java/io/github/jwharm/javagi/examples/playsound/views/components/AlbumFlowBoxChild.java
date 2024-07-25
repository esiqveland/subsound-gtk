package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import org.gnome.gtk.*;

public class AlbumFlowBoxChild extends FlowBoxChild {
    private final static int COVER_SIZE = 128;

    private final AlbumInfo albumInfo;

    public AlbumFlowBoxChild(
            AlbumInfo albumInfo,
            ThumbLoader thumbLoader
    ) {
        super();
        this.albumInfo = albumInfo;
        Widget albumCover = albumInfo.coverArt()
                .map(coverArt -> {
                    Widget w = new AlbumArt(coverArt, thumbLoader, COVER_SIZE);
                    return w;
                })
                .orElseGet(() -> {
                    Widget w = AlbumArt.placeholderImage(COVER_SIZE);
                    return w;
                });
        var albumTitle = Label.builder().setLabel(albumInfo.name()).build();
        var albumArtist = Label.builder().setLabel(albumInfo.artistName()).build();

        var box = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHalign(Align.CENTER)
                .build();
        box.append(albumCover);
        box.append(albumTitle);
        box.append(albumArtist);

        this.setChild(box);
    }


}
