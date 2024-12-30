package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.FlowBoxChild;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import static com.github.subsound.utils.Utils.addHover;
import static com.github.subsound.utils.Utils.cssClasses;

public class AlbumFlowBoxChild extends FlowBoxChild {
    private final static int COVER_SIZE = 192;

    private final AppManager thumbLoader;
    private final ArtistAlbumInfo albumInfo;
    private final Widget albumCover;

    public AlbumFlowBoxChild(
            AppManager thumbLoader,
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
