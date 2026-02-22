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
import org.jspecify.annotations.Nullable;
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
    private CoverArt artwork;
    private final Picture image;
    private final int size;
    private final AtomicBoolean clickable = new AtomicBoolean(true);
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);

    private final static Map<Boolean, Texture> placeHolderCache = new ConcurrentHashMap<>();

    private static Texture getPlaceholderTexture() {
        return placeHolderCache.computeIfAbsent(true, (key) -> {
            try {
                var bytes = mustReadBytes("images/album-placeholder.png");
                var gioStream = MemoryInputStream.fromData(bytes);
                // load at a reasonable resolution (2 * 128)
                var loadSize = 2 * 128;
                var pixbuf = Pixbuf.fromStreamAtScale(gioStream, loadSize, loadSize, true, new Cancellable());
                return Texture.forPixbuf(pixbuf);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static Grid placeholderImage(int size) {
            var texture = getPlaceholderTexture();
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

    public static RoundedAlbumArt resolveCoverArt(AppManager thumbLoader, Optional<CoverArt> coverArt, int size, boolean clickable) {
        return new RoundedAlbumArt(coverArt, thumbLoader, size).setClickable(clickable);
    }

    public RoundedAlbumArt(Optional<CoverArt> artwork, AppManager thumbLoader, int size) {
        this(artwork.orElse(null), thumbLoader, size);
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

        // See: https://docs.gtk.org/gtk4/class.Picture.html
        this.image = new Picture();
        this.image.setContentFit(ContentFit.COVER);
        this.image.setSizeRequest(size, size);
        this.image.setOverflow(Overflow.HIDDEN);
        this.image.addCssClass("rounded");

        if (this.artwork == null) {
            this.image.setPaintable(getPlaceholderTexture());
        }

        var click = addClick(
                this,
                () -> {
                    if (!clickable.get() || this.artwork == null) {
                        return;
                    }
                    log.info("{}: addClick: enabled={} id={}", this.getClass().getSimpleName(), clickable.get(), this.artwork.coverArtId());
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
            if (isLoaded.get() || this.artwork == null) {
                return;
            }
            log.info("%s: onMap: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(this.image).thenAccept(_ -> isLoaded.set(true));
        });

        var clamp = new Clamp();
        clamp.setChild(this.image);
        clamp.setMaximumSize(size);
        this.append(clamp);
    }

    public RoundedAlbumArt setClickable(boolean clickable) {
        this.clickable.set(clickable);
        return this;
    }

    public void update(Optional<CoverArt> newCoverArt) {
        update(newCoverArt.orElse(null));
    }

    public void update(@Nullable CoverArt newArtwork) {
        var oldArtwork = this.artwork;
        // Skip if same cover art ID
        if (oldArtwork != null && newArtwork != null && oldArtwork.coverArtId().equals(newArtwork.coverArtId())) {
            return;
        }
        this.artwork = newArtwork;
        this.isLoaded.set(false);
        if (newArtwork == null) {
            var tex = getPlaceholderTexture();
            Utils.runOnMainThread(() -> image.setPaintable(tex));
        } else if (this.getMapped()) {
            startLoad(this.image).thenAccept(_ -> isLoaded.set(true));
        }
        // If not mapped and artwork non-null: onMap will trigger load
    }

    public CompletableFuture<Void> startLoad(Picture image) {
        var artworkSnapshot = this.artwork;
        if (artworkSnapshot == null) {
            return CompletableFuture.completedFuture(null);
        }
        return this.thumbLoader.getThumbnailCache().loadPixbuf(artworkSnapshot, this.size)
                .thenAccept(storedImage -> {
                    var texture = storedImage.texture();
                    // artwork changed while loading:
                    if (this.artwork != artworkSnapshot) {
                        return;
                    }
                    Utils.runOnMainThread(() -> {
                        image.setPaintable(texture);
                        image.setSizeRequest(size, size);
                    });
                })
                .exceptionally(e -> {
                    log.error("Failed to load album art: id={}", artworkSnapshot.coverArtId(), e);
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
