package org.subsound.ui.views;

import org.subsound.app.state.AppManager;
import org.subsound.app.state.AppManager.AlbumInfo;
import org.subsound.app.state.PlayerAction;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.ui.components.BoxHolder;
import org.subsound.ui.components.FutureLoader;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AlbumInfoLoader extends Box {
    private final AppManager appManager;
    private final AtomicReference<String> albumId = new AtomicReference<>("");
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ThumbnailCache thumbLoader;

    private final BoxHolder<FutureLoader<AlbumInfo, AlbumInfoPage>> viewHolder = new BoxHolder<>();

    public AlbumInfoLoader(
            ThumbnailCache thumbLoader,
            AppManager appManager,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.onAction = onAction;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
        this.append(this.viewHolder);
    }

    public synchronized AlbumInfoLoader setAlbumId(String albumId) {
        if (albumId.equals(this.albumId.get())) {
            return this;
        }

        doLoad(albumId);
        return this;
    }

    private void doLoad(String albumId) {
        this.albumId.set(albumId);
        var future = this.appManager.getAlbumInfoAsync(albumId);

        var loader = new FutureLoader<>(future, albumInfo -> new AlbumInfoPage(
                this.appManager,
                albumInfo,
                this.onAction
        ));
        this.viewHolder.setChild(loader);
    }
}
