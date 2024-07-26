package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbLoader;
import org.gnome.gtk.*;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class AlbumFlowBoxChild extends FlowBoxChild {
    private final static int COVER_SIZE = 128;

    private final ThumbLoader thumbLoader;
    private final ArtistAlbumInfo albumInfo;
    private final Widget albumCover;

    public AlbumFlowBoxChild(
            ThumbLoader thumbLoader,
            ArtistAlbumInfo albumInfo
    ) {
        super();
        this.addCssClass("card");
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.albumCover = RoundedAlbumArt.resolveCoverArt(this.thumbLoader, albumInfo.coverArt(), COVER_SIZE);
        var albumTitle = Label.builder()
                .setLabel(albumInfo.name())
                .setCssClasses(cssClasses("title-2"))
                .build();
        var albumArtist = Label.builder()
                .setLabel(albumInfo.artistName())
                .setCssClasses(cssClasses("title-4", "dim-label"))
                .build();

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
