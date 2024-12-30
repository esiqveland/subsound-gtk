package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.CoverArt;
import com.github.subsound.utils.Utils;
import org.gnome.adw.Clamp;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.Cancellable;
import org.gnome.gio.MemoryInputStream;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.Grid;
import org.gnome.gtk.Image;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Picture;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.subsound.utils.Utils.addClick;
import static com.github.subsound.utils.Utils.addHover2;
import static com.github.subsound.utils.Utils.mustReadBytes;


public class RoundedAlbumArt extends Box {
    private static final Logger log = LoggerFactory.getLogger(RoundedAlbumArt.class);

    private final AppManager thumbLoader;
    private final CoverArt artwork;
    private final Picture image;
    private final Grid grid;
    private final AtomicBoolean hasLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private final int size;

    private final static Map<Boolean, Pixbuf> placeHolderCache = new ConcurrentHashMap<>();
    public static Grid placeholderImage(int size) {
            Pixbuf pixbuf = placeHolderCache.computeIfAbsent(true, (key) -> {
                try {
                    var bytes = mustReadBytes("images/album-placeholder.png");
                    var gioStream = MemoryInputStream.fromData(bytes);
                    //Pixbuf pixbufloader = Pixbuf.fromFileAtSize("src/main/resources/images/album-placeholder.png", size, size);
                    var pixbufloader = Pixbuf.fromStreamAtScale(gioStream, size, size, true, new Cancellable());
                    return pixbufloader;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Texture texture = Texture.forPixbuf(pixbuf);
            var image = Image.fromPaintable(texture);
            image.setSizeRequest(size, size);
            var box = Grid.builder()
                    .setHexpand(false)
                    .setVexpand(true)
                    .setHalign(Align.CENTER)
                    .setValign(Align.CENTER)
                    .build();
            box.setSizeRequest(size, size);
            box.setOverflow(Overflow.HIDDEN);
            box.addCssClass("rounded");
            box.attach(image, 0, 0, 1, 1);
            return box;
    }

    public static Widget resolveCoverArt(AppManager thumbLoader, Optional<CoverArt> coverArt, int size) {
        if (coverArt.isPresent()) {
            return new RoundedAlbumArt(coverArt.get(), thumbLoader, size);
        } else {
            return placeholderImage(size);
        }
    }

    public RoundedAlbumArt(CoverArt artwork, AppManager thumbLoader, int size) {
        super(Orientation.VERTICAL, 0);
        this.artwork = artwork;
        this.thumbLoader = thumbLoader;
        this.size = size;

        //this.setSizeRequest(size, size);
        this.setHexpand(false);
        this.setVexpand(true);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
//        this.addCssClass("rounded");

        this.grid = Grid.builder()
                .setHexpand(false)
                .setVexpand(true)
                .setHalign(Align.CENTER)
                .setValign(Align.CENTER)
                .build();
        this.grid.setSizeRequest(size, size);
        this.grid.setOverflow(Overflow.HIDDEN);
        this.grid.addCssClass("rounded");

        // See: https://docs.gtk.org/gdk-pixbuf/class.Picture.html
        this.image = Picture.builder()
                .setCanShrink(true)
                .setContentFit(ContentFit.COVER)
                .setWidthRequest(size)
                .setHeightRequest(size)
                .build();
        this.image.setSizeRequest(size, size);

        this.onMap(() -> {
            log.info("%s: onMap: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(this.image);
        });
        addClick(
                this,
                () -> log.info("%s: addClick: id=%s".formatted(this.getClass().getSimpleName(), this.artwork))
        );

        //var className = "now-playing-overlay-icon";
        var className = Classes.activatable.className();
        addHover2(
                this,
                () -> this.addCssClass(className),
                () -> this.removeCssClass(className)
        );


        this.grid.attach(image, 0, 0, 1, 1);
        var clamp = Clamp.builder().setChild(this.grid).setMaximumSize(size).build();
        this.append(clamp);
    }

    public void startLoad(Picture image) {
        this.thumbLoader.getThumbnailCache().loadPixbuf(this.artwork, this.size)
                .thenAccept(pixbuf -> {
                    //Texture texture = Texture.forPixbuf(pixbuf);
                    Utils.runOnMainThread(() -> {
                        //image.setPaintable(texture);
                        image.setPixbuf(pixbuf.pixbuf());
                        image.setSizeRequest(size, size);
                    });
                });
    }

//    public static class CoverPaintable implements Paintable {
//        @Override
//        public void snapshot(Snapshot snapshotOrig, double width, double height) {
//            org.gnome.gtk.Snapshot snapshot = (org.gnome.gtk.Snapshot) snapshotOrig;
//            float radius = 9.0f;
//
//            var w_s = width;
//            var h_s = height;
//
//            float ww = (float) width;
//            float hh = (float) height;
//
//            var rect = new Rect().init(0, 0, ww, hh);
//            var roundedRect = new RoundedRect();
//            roundedRect.initFromRect(rect, radius);
//
//            var color = new RGBA();
//            color.parse("");
//            snapshot.pushRoundedClip(roundedRect);
//            snapshot.appendScaledTexture();
//            snapshot.pop();
//        }
//    }
}
