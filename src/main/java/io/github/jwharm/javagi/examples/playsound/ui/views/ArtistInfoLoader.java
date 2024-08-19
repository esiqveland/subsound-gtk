package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ArtistInfoLoader extends Box {
    private final ThumbnailCache thumbLoader;
    private final ServerClient client;
    private final String artistId = "";
    private final AtomicReference<ArtistInfoFlowBox> viewHolder = new AtomicReference<>();
    private Consumer<ArtistAlbumInfo> onAlbumSelected;

    public ArtistInfoLoader(ThumbnailCache thumbLoader, ServerClient client, Consumer<ArtistAlbumInfo> onAlbumSelected) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.onAlbumSelected = onAlbumSelected;
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
            var next = new ArtistInfoFlowBox(
                    thumbLoader,
                    info,
                    this::onAlbumSelected
            );
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

    public void setOnAlbumSelected(Consumer<ArtistAlbumInfo> onAlbumSelected) {
        this.onAlbumSelected = onAlbumSelected;
    }

    private void onAlbumSelected(ArtistAlbumInfo selected) {
        var handler = this.onAlbumSelected;
        if (handler == null) {
            return;
        }
        handler.accept(selected);
    }
}
