package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.CoverArt;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.AlbumIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.ArtistIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import com.github.subsound.ui.components.AppNavigation.AppRoute;
import com.github.subsound.ui.components.AppNavigation.AppRoute.RouteAlbumInfo;
import com.github.subsound.ui.components.AppNavigation.AppRoute.RouteArtistInfo;
import com.github.subsound.ui.components.AppNavigation.AppRoute.RoutePlaylistsOverview;
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
import java.util.concurrent.CompletableFuture;
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
    private final int size;
    private final AtomicBoolean clickable = new AtomicBoolean(true);
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);

    private final static Map<Boolean, Texture> placeHolderCache = new ConcurrentHashMap<>();

    public static Grid placeholderImage(int size) {
            var texture = placeHolderCache.computeIfAbsent(true, (key) -> {
                try {
                    var bytes = mustReadBytes("images/album-placeholder.png");
                    var gioStream = MemoryInputStream.fromData(bytes);
                    //Pixbuf pixbufloader = Pixbuf.fromFileAtSize("src/main/resources/images/album-placeholder.png", size, size);
                    // load at twice the requested size, as the texture for some reason looks very bad in some situations
                    // at the requested size.
                    var loadSize = 2 * size;
                    var pixbuf = Pixbuf.fromStreamAtScale(gioStream, loadSize, loadSize, true, new Cancellable());
                    return Texture.forPixbuf(pixbuf);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            var image = Image.fromPaintable(texture);
            image.setSizeRequest(size, size);

            var box = new Grid();
            box.setHexpand(false);
            box.setVexpand(true);
            box.setHalign(Align.CENTER);
            box.setValign(Align.CENTER);
            box.setSizeRequest(size, size);
            box.setOverflow(Overflow.HIDDEN);
            box.addCssClass("rounded");
            box.attach(image, 0, 0, 1, 1);
            return box;
    }

    public static Widget resolveCoverArt(AppManager thumbLoader, Optional<CoverArt> coverArt, int size, boolean clickable) {
        if (coverArt.isPresent()) {
            return new RoundedAlbumArt(coverArt.get(), thumbLoader, size).setClickable(clickable);
        } else {
            return placeholderImage(size);
        }
    }

    public RoundedAlbumArt(CoverArt artwork, AppManager thumbLoader, int size) {
        super(Orientation.VERTICAL, 0);
        this.artwork = artwork;
        this.thumbLoader = thumbLoader;
        this.size = size;

        this.setHexpand(false);
        this.setVexpand(true);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
//        this.addCssClass("rounded");

        this.grid = new Grid();
        this.grid.setHexpand(false);
        this.grid.setVexpand(true);
        this.grid.setHalign(Align.CENTER);
        this.grid.setValign(Align.CENTER);
        this.grid.setSizeRequest(size, size);
        this.grid.setOverflow(Overflow.HIDDEN);
        this.grid.addCssClass("rounded");

        // See: https://docs.gtk.org/gtk4/class.Picture.html
        this.image = new Picture();
        this.image.setContentFit(ContentFit.COVER);
        this.image.setSizeRequest(size, size);

        var click = addClick(
                this,
                () -> {
                    log.info("{}: addClick: enabled={} id={}", this.getClass().getSimpleName(), clickable.get(), this.artwork.coverArtId());
                    if (!clickable.get()) {
                        return;
                    }
                    var currentArtwork = this.artwork;
                    AppRoute route = switch (currentArtwork.identifier().orElse(null)) {
                        case AlbumIdentifier a -> new RouteAlbumInfo(a.albumId());
                        case ArtistIdentifier a -> new RouteArtistInfo(a.artistId());
                        case PlaylistIdentifier p -> new RoutePlaylistsOverview(Optional.of(p.playlistId()));
                        case SongIdentifier p -> {
                            log.info("unhandled song click: p.songId() = {}", p.songId());
                            yield null;
                        }
                        case null -> null;
                    };
                    if (route != null) {
                        this.thumbLoader.navigateTo(route);
                    }
                }
        );

        //var className = "now-playing-overlay-icon";
        var className = Classes.activatable.className();
        var hoverC = addHover2(
                () -> this.addCssClass(className),
                () -> this.removeCssClass(className)
        );
        this.addController(hoverC.eventController());
        this.onDestroy(() -> {
            var signal = click.signalConnection();
            if (signal != null) {
                signal.disconnect();
            }
            hoverC.disconnect();
            this.removeController(hoverC.eventController());
        });

        // wait with loading until we actually get mapped:
        this.onMap(() -> {
            if (isLoaded.get()) {
                return;
            }
            log.info("%s: onMap: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(this.image).thenAccept(_ -> isLoaded.set(true));
        });

        this.grid.attach(image, 0, 0, 1, 1);
        var clamp = new Clamp();
        clamp.setChild(this.grid);
        clamp.setMaximumSize(size);
        this.append(clamp);
    }

    public RoundedAlbumArt setClickable(boolean clickable) {
        this.clickable.set(clickable);
        return this;
    }

    public CompletableFuture<Void> startLoad(Picture image) {
        return this.thumbLoader.getThumbnailCache().loadPixbuf(this.artwork, this.size)
                .thenAccept(storedImage -> {
                    //log.info("startLoad: size={} id={}", storedImage.texture().getWidth(), this.artwork.coverArtId());
                    var texture = storedImage.texture();
                    Utils.runOnMainThread(() -> {
                        image.setPaintable(texture);
                        image.setSizeRequest(size, size);
                    });
                })
                .exceptionally(e -> {
                    log.error("Failed to load album art: id={}", this.artwork.coverArtId(), e);
                    return null;
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
