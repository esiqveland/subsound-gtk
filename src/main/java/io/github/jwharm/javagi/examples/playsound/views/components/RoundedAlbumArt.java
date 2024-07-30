package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.base.GErrorException;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gdkpixbuf.PixbufLoader;
import org.gnome.glib.GLib;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.doAsync;

public class RoundedAlbumArt extends Grid {
    private static final Logger log = LoggerFactory.getLogger(RoundedAlbumArt.class);

    private final ThumbnailCache thumbLoader;
    private final CoverArt artwork;
    private final Picture image;
    private final AtomicBoolean hasLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);

    public static Grid placeholderImage(int size) {
        try {
            Pixbuf pixbuf = Pixbuf.fromFileAtSize("src/main/resources/images/album-placeholder.png", size, size);
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
        } catch (GErrorException e) {
            throw new RuntimeException(e);
        }
    }

    public static Widget resolveCoverArt(ThumbnailCache thumbLoader, Optional<CoverArt> coverArt, int size) {
        if (coverArt.isPresent()) {
            return new RoundedAlbumArt(coverArt.get(), thumbLoader, size);
        } else {
            return placeholderImage(size);
        }
    }

    public RoundedAlbumArt(CoverArt artwork, ThumbnailCache thumbLoader, int size) {
        super();
        this.setSizeRequest(size, size);
        this.setHexpand(false);
        this.setVexpand(false);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
        this.addCssClass("rounded");

        this.artwork = artwork;
        this.thumbLoader = thumbLoader;
        // See: https://docs.gtk.org/gdk-pixbuf/class.PixbufLoader.html
        var loader = PixbufLoader.builder().build();
        loader.setSize(size, size);
        var pixbuf = loader.getPixbuf();
        //pixbuf.scaleSimple(size, size, InterpType.BILINEAR);

        // See: https://docs.gtk.org/gdk-pixbuf/class.Picture.html
        this.image = Picture.forPixbuf(pixbuf);
        //this.image = Picture.forPaintable(Texture.forPixbuf(pixbuf));
        this.image.setSizeRequest(size, size);
        this.image.setContentFit(ContentFit.COVER);

        loader.onAreaUpdated((x, y, h, w) -> {
            if (!isUpdating.compareAndSet(false, true)) {
                return;
            }
            try {
                // apparently we need to touch the image and make it redraw somehow:
                this.image.setPixbuf(loader.getPixbuf());
            } finally {
                isUpdating.set(false);
            }
        });

        this.onRealize(() -> {
            log.info("%s: onRealize: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(loader);
        });
        this.onShow(() -> {
            log.info("%s: onShow: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(loader);
        });

        this.attach(image, 0, 0, 1, 1);
    }

    public void startLoad(PixbufLoader loader) {
        CompletableFuture<Boolean> loadingFuture = doAsync(() -> {
                    if (hasLoaded.get()) {
                        return false;
                    }
                    if (isLoading.get()) {
                        return false;
                    }
                    try {
                        thumbLoader.load(this.artwork, buffer -> {
                            if (buffer == null || buffer.length == 0) {
                                return;
                            }
                            GLib.idleAddOnce(() -> {
                                try {
                                    loader.write(buffer);
                                } catch (GErrorException e) {
                                    throw new RuntimeException("error writing: coverArtId=%s link=%s".formatted(artwork.coverArtId(), artwork.coverArtLink()), e);
                                }
                            });
                        });
                        log.info("%s: id=%s: LOADED OK".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
                    } finally {
                        isLoading.set(false);
                        GLib.idleAddOnce(() -> {
                            try {
                                loader.close();
                            } catch (GErrorException e) {
                                throw new RuntimeException("error closing: coverArtId=%s link=%s".formatted(artwork.coverArtId(), artwork.coverArtLink()), e);
                            }
                        });
                        hasLoaded.set(true);
                        this.image.queueDraw();
                    }
                    return true;
                })
                .exceptionallyAsync(ex -> {
                    log.error("error loading img: {}", ex.getMessage(), ex);
                    throw new RuntimeException(ex);
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
