package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.base.GErrorException;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import org.gnome.gdk.Paintable;
import org.gnome.gdkpixbuf.PixbufLoader;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class AlbumArt extends Box {
    private static final int DEFAULT_SIZE = 400;
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final ThumbLoader THUMB_LOADER = new ThumbLoader();

    private final CoverArt artwork;
    private final AtomicBoolean hasLoaded = new AtomicBoolean(false);
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final Image image;


    // not present artwork returns a placeholder image
    public static Box placeholderImage() {
        return placeholderImage(DEFAULT_SIZE);
    }

    public static Box placeholderImage(int size) {
        var image = Image.builder().setPaintable(Paintable.newEmpty(size, size)).build();
        var box = Box.builder().build();
    }

    public AlbumArt(CoverArt artwork) {
        this(artwork, 400);
    }

    public AlbumArt(CoverArt artwork, int size) {
        super(Orientation.VERTICAL, 5);
        this.artwork = artwork;
        // See: https://docs.gtk.org/gdk-pixbuf/class.PixbufLoader.html
        var loader = PixbufLoader.builder().build();
        this.image = Image.fromPixbuf(loader.getPixbuf());
        this.image.setSizeRequest(size, size);
        loader.onAreaPrepared(() -> {
            // apparently we need to touch the image and make it redraw somehow:
            this.image.setFromPixbuf(loader.getPixbuf());
        });

        this.onRealize(() -> {
            System.out.println("%s: onRealize: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(loader);
        });
        this.onShow(() -> {
            System.out.println("%s: onShow: id=%s".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            this.startLoad(loader);
        });

        this.setHexpand(true);
        this.setVexpand(true);
        this.append(image);
    }

    public void startLoad(PixbufLoader loader) {
        CompletableFuture<Void> loadingFuture = CompletableFuture.runAsync(() -> {
            if (hasLoaded.get()) {
                return;
            }
            if (isLoading.get()) {
                return;
            }
            try {
                THUMB_LOADER.loadThumb(this.artwork.coverArtLink(), buffer -> {
                    try {
                        loader.write(buffer);
                    } catch (GErrorException e) {
                        throw new RuntimeException(e);
                    }
                });
                System.out.println("%s: id=%s: LOADED OK".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
            } finally {
                isLoading.set(false);
                try {
                    loader.close();
                    hasLoaded.set(true);
                } catch (GErrorException e) {
                    throw new RuntimeException(e);
                }
            }
        }, EXECUTOR);
    }
}
