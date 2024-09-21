package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;
import org.gnome.pango.EllipsizeMode;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.addHover;
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
        //setCssClasses(cssClasses("card", "activatable"));
        addHover(
                this,
                () -> Utils.runOnMainThread(() -> {
                    this.addCssClass("card");
                    this.addCssClass("activatable");
                }),
                () -> Utils.runOnMainThread(() -> {
                    this.removeCssClass("card");
                    this.removeCssClass("activatable");
                })
        );
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.albumCover = RoundedAlbumArt.resolveCoverArt(this.thumbLoader, this.albumInfo.coverArt(), COVER_SIZE);
        var albumTitle = Label.builder()
                .setLabel(albumInfo.name())
                .setCssClasses(cssClasses("heading"))
                .setEllipsize(EllipsizeMode.END)
                .setHalign(Align.START)
                .setMaxWidthChars(20)
                .build();
        var albumArtist = Label.builder()
                .setLabel(albumInfo.artistName())
                .setCssClasses(cssClasses("dim-label"))
                .setHalign(Align.START)
                .build();

        var yearLabel = albumInfo.year().map(year -> Label.builder()
                .setLabel("" + year)
                .setHalign(Align.START)
                .setCssClasses(cssClasses("dim-label"))
                .build());

        int margin = 6;
        var box = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHalign(Align.CENTER)
                .setSpacing(margin)
                .setMarginStart(margin)
                .setMarginTop(margin)
                .setMarginEnd(margin)
                .setMarginBottom(margin)
                .setSensitive(true)
                .build();
        box.append(albumCover);
        box.append(albumTitle);
        //box.append(albumArtist);
        yearLabel.ifPresent(box::append);


        this.setChild(box);
    }


}
