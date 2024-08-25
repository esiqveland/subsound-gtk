package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.addHover;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class OverviewAlbumChild extends Box {
    private final static int COVER_SIZE = 128;

    private final ThumbnailCache thumbLoader;
    private final Label albumTitle;
    private final Label albumArtist;
    private final Label yearLabel;
    private final AlbumCoverHolder albumCoverHolder;

    private ArtistAlbumInfo albumInfo;

    public void setAlbumInfo(ArtistAlbumInfo albumInfo) {
        if (this.albumInfo != null) {
            // stops blinking on hover when the ListItem is updated again:
            if (this.albumInfo.id().equals(albumInfo.id())) {
                return;
            }
        }
        this.albumInfo = albumInfo;
        this.albumTitle.setLabel(albumInfo.name());
        this.albumArtist.setLabel(albumInfo.artistName());
        this.yearLabel.setLabel(albumInfo.year().map(String::valueOf).orElse(""));
        this.albumCoverHolder.setArtwork(albumInfo.coverArt());
    }

    public OverviewAlbumChild(
            ThumbnailCache thumbLoader
    ) {
        super(Orientation.VERTICAL, 0);
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
        this.albumCoverHolder = new AlbumCoverHolder(thumbLoader);
        this.albumTitle = Label.builder()
                .setLabel("")
                .setCssClasses(cssClasses("heading"))
                .setEllipsize(EllipsizeMode.END)
                .setHalign(Align.START)
                .setMaxWidthChars(20)
                .build();
        this.albumArtist = Label.builder()
                .setLabel("")
                .setCssClasses(cssClasses("dim-label"))
                .setHalign(Align.START)
                .build();


        this.yearLabel = Label.builder()
                .setLabel("")
                .setHalign(Align.START)
                .setCssClasses(cssClasses("dim-label"))
                .build();

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
        box.append(albumCoverHolder);
        box.append(albumTitle);
        //box.append(albumArtist);
        box.append(yearLabel);
        this.append(box);
    }



    public static class AlbumCoverHolder extends Box {
        private static final Widget PLACEHOLDER = RoundedAlbumArt.placeholderImage(COVER_SIZE);
        private final ThumbnailCache thumbLoader;
        private final AtomicReference<Widget> ref = new AtomicReference<>();
        private Optional<CoverArt> artwork;

        public AlbumCoverHolder(ThumbnailCache thumbLoader) {
            super(Orientation.VERTICAL, 0);
            this.thumbLoader = thumbLoader;
            this.ref.set(PLACEHOLDER);
            this.append(PLACEHOLDER);
        }

        public void setArtwork(Optional<CoverArt> artwork) {
            this.artwork = artwork;
            var prev = this.ref.get();
            if (prev != null) {
                this.remove(prev);
            }
            Widget next = this.artwork
                    .map(coverArt -> new RoundedAlbumArt(coverArt, this.thumbLoader, COVER_SIZE))
                    .map(a -> (Widget)a)
                    .orElse(PLACEHOLDER);
            this.ref.set(next);
            this.append(next);
        }
    }
}
