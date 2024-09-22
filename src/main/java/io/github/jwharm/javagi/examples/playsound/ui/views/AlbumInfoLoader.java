package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.BoxHolder;
import io.github.jwharm.javagi.examples.playsound.ui.components.FutureLoader;
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

    private final BoxHolder<FutureLoader<AlbumInfo, AlbumInfoBox>> viewHolder = new BoxHolder<>();

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
            var info = this.appManager.getClient().getAlbumInfo(albumId);

            return info;
        });

        // just make sure we didnt change albumId in the meantime we were loading data:
        if (!albumId.equals(this.albumId.get())) {
            return;
        }

        var loader = new FutureLoader<>(future, albumInfo -> new AlbumInfoBox(
                this.thumbLoader,
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
        child.getMainWidget().ifPresent(albumInfoBox -> {
            albumInfoBox.updateAppState(state);
        });
    }
}
