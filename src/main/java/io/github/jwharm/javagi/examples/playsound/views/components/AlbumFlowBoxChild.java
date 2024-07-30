package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import org.gnome.gtk.*;
import org.gnome.pango.EllipsizeMode;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class AlbumFlowBoxChild extends FlowBoxChild {
    private final static int COVER_SIZE = 192;

    private final ThumbnailCache thumbLoader;
    private final ArtistAlbumInfo albumInfo;
    private final Widget albumCover;

    public AlbumFlowBoxChild(
            ThumbnailCache thumbLoader,
            ArtistAlbumInfo albumInfo
    ) {
        super();
        //this.addCssClass("card");
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.albumCover = RoundedAlbumArt.resolveCoverArt(this.thumbLoader, albumInfo.coverArt(), COVER_SIZE);
        var albumTitle = Label.builder()
                .setLabel(albumInfo.name())
                .setCssClasses(cssClasses("heading"))
                .setEllipsize(EllipsizeMode.END)
                .setMaxWidthChars(20)
                .build();
        var albumArtist = Label.builder()
                .setLabel(albumInfo.artistName())
                .setCssClasses(cssClasses("dim-label"))
                .build();

        var yearLabel = albumInfo.year().map(year -> Label.builder()
                .setLabel("" + year)
                .setCssClasses(cssClasses("dim-label"))
                .build());

        var box = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHalign(Align.CENTER)
                .build();
        box.append(albumCover);
        box.append(albumTitle);
        //box.append(albumArtist);
        yearLabel.ifPresent(box::append);

        this.setChild(box);
    }


}
