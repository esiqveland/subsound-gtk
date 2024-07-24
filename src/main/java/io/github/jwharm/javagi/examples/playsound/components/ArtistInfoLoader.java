package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class ArtistInfoLoader extends Box {
    private final ServerClient client;
    private final String artistId = "";
    private final AtomicReference<ArtistInfoBox> viewHolder = new AtomicReference<>();

    public ArtistInfoLoader(ServerClient client) {
        super(Orientation.VERTICAL, 0);
        this.client = client;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
    }

    public synchronized ArtistInfoLoader setArtistId(String artistId) {
        if (artistId.equals(this.artistId)) {
            return this;
        }

        doLoad(artistId);
        return this;
    }

    private void replaceArtistInfo(ArtistInfo info) {
        var current = viewHolder.get();
        Utils.runOnMainThread(() -> {
            if (current != null) {
                this.remove(current);
            }
            var next = new ArtistInfoBox(info);
            this.viewHolder.set(next);
            this.append(next);
        });
    }

    private CompletableFuture<ArtistInfo> doLoad(String artistId) {
        return CompletableFuture.supplyAsync(() -> {
            var info = this.client.getArtistInfo(artistId);

            // just make sure we didnt change artist in the meantime we were loading data:
            if (info.id().equals(artistId)) {
                this.replaceArtistInfo(info);
            }
            return info;
        });
    }
}
