package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class AlbumInfoLoader extends Box {
    private final ServerClient client;
    private final String albumId = "";
    private final AtomicReference<AlbumInfoBox> viewHolder = new AtomicReference<>();

    public AlbumInfoLoader(ServerClient client) {
        super(Orientation.VERTICAL, 0);
        this.client = client;
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
            var next = new AlbumInfoBox(info);
            this.viewHolder.set(next);
            this.append(next);
        });
    }

    private CompletableFuture<AlbumInfo> doLoad(String albumId) {
        return CompletableFuture.supplyAsync(() -> {
            var info = this.client.getAlbumInfo(albumId);

            // just make sure we didnt change artist in the meantime we were loading data:
            if (info.id().equals(albumId)) {
                this.replaceArtistInfo(info);
            }
            return info;
        });
    }
}
