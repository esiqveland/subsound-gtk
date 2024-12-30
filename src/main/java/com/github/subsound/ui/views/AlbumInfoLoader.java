package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.AlbumInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.FutureLoader;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AlbumInfoLoader extends Box implements AutoCloseable, AppManager.StateListener {
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
        this.onMap(() -> this.appManager.addOnStateChanged(this));
        this.onUnmap(() -> this.appManager.removeOnStateChanged(this));
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
        var future =  CompletableFuture.supplyAsync(() -> {
            var info = this.appManager.useClient(client -> client.getAlbumInfo(albumId));

            return info;
        });

        // just make sure we didnt change albumId in the meantime we were loading data:
        if (!albumId.equals(this.albumId.get())) {
            return;
        }

        var loader = new FutureLoader<>(future, albumInfo -> new AlbumInfoPage(
                this.appManager,
                albumInfo,
                this.onAction
        ));
        this.viewHolder.setChild(loader);
    }

    @Override
    public void close() throws Exception {
        this.appManager.removeOnStateChanged(this);
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var child = this.viewHolder.getChild();
        if (child == null) {
            return;
        }
        child.getMainWidget().ifPresent(albumInfoPage -> {
            albumInfoPage.updateAppState(state);
        });
    }
}
