package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gobject.SignalConnection;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AlbumInfoLoader extends Box implements AutoCloseable, AppManager.StateListener {
    private final AppManager client;
    private final String albumId = "";
    private final AtomicReference<AlbumInfoBox> viewHolder = new AtomicReference<>();
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ThumbnailCache thumbLoader;

    public AlbumInfoLoader(
            ThumbnailCache thumbLoader,
            AppManager client,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.onAction = onAction;
        this.onMap(() -> this.client.addOnStateChanged(this));
        this.onUnmap(() -> this.client.removeOnStateChanged(this));
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
    }

    public synchronized AlbumInfoLoader setAlbumId(String albumId) {
        if (albumId.equals(this.albumId)) {
            return this;
        }

        doLoad(albumId);
        return this;
    }

    private void replaceArtistInfo(AlbumInfo info) {
        var current = viewHolder.get();
        Utils.runOnMainThread(() -> {
            if (current != null) {
                this.remove(current);
            }
            var next = new AlbumInfoBox(thumbLoader, info, onAction);
            this.viewHolder.set(next);
            this.append(next);
        });
    }

    private CompletableFuture<AlbumInfo> doLoad(String albumId) {
        return CompletableFuture.supplyAsync(() -> {
            var info = this.client.getClient().getAlbumInfo(albumId);

            // just make sure we didnt change artist in the meantime we were loading data:
            if (info.id().equals(albumId)) {
                this.replaceArtistInfo(info);
            }
            return info;
        });
    }

    @Override
    public void close() throws Exception {
        this.client.removeOnStateChanged(this);
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var box = this.viewHolder.get();
        if (box != null) {
            box.updateAppState(state);
        }
    }
}
