package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.base.GErrorException;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import org.gnome.gdkpixbuf.PixbufLoader;
import org.gnome.glib.GLib;
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
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private final Image image;


    // not present artwork returns a placeholder image
    public static Box placeholderImage() {
        return placeholderImage(DEFAULT_SIZE);
    }

    public static Box placeholderImage(int size) {
        Image image = Image.fromFile("src/main/resources/images/album-placeholder.png");
        image.setSizeRequest(size, size);
        var box = Box.builder().setHexpand(true).setVexpand(true).build();
        box.append(image);
        return box;
    }

    public AlbumArt(CoverArt artwork) {
        this(artwork, 400);
    }

    public AlbumArt(CoverArt artwork, int size) {
        super(Orientation.VERTICAL, 0);
        this.artwork = artwork;
        // See: https://docs.gtk.org/gdk-pixbuf/class.PixbufLoader.html
        var loader = PixbufLoader.builder().build();
        this.image = Image.fromPixbuf(loader.getPixbuf());
        this.image.setSizeRequest(size, size);

//        loader.onAreaPrepared(() -> {
//            if (!isUpdating.compareAndSet(false, true)) {
//                return;
//            }
//            try {
//                // apparently we need to touch the image and make it redraw somehow:
//                this.image.setFromPixbuf(loader.getPixbuf());
//            } finally {
//                isUpdating.set(false);
//            }
//        });
        loader.onAreaUpdated((x, y, h, w) -> {
            if (!isUpdating.compareAndSet(false, true)) {
                return;
            }
            try {
                // apparently we need to touch the image and make it redraw somehow:
                this.image.setFromPixbuf(loader.getPixbuf());
            } finally {
                isUpdating.set(false);
            }
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
                            if (buffer == null || buffer.length == 0) {
                                return;
                            }
                            GLib.idleAddOnce(() -> {
                                try {
                                    loader.write(buffer);
                                } catch (GErrorException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        });
                        System.out.println("%s: id=%s: LOADED OK".formatted(this.getClass().getSimpleName(), this.artwork.coverArtId()));
                    } finally {
                        isLoading.set(false);
                        GLib.idleAddOnce(() -> {
                            try {
                                loader.close();
                            } catch (GErrorException e) {
                                throw new RuntimeException(e);
                            }
                        });
                        hasLoaded.set(true);
                        this.image.queueDraw();
                    }
                }, EXECUTOR)
                .exceptionallyAsync(ex -> {
                    System.out.printf("error loading img: %s", ex.getMessage());
                    ex.printStackTrace();
                    throw new RuntimeException(ex);
                });

    }
}
