package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.FutureLoader;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ArtistInfoLoader extends Box {
    private final ThumbnailCache thumbLoader;
    private final AppManager client;
    private final AtomicReference<String> artistId = new AtomicReference<>("");
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final BoxHolder<FutureLoader<ArtistInfo, ArtistInfoFlowBox>> holder;

    public ArtistInfoLoader(ThumbnailCache thumbLoader, AppManager client, Consumer<ArtistAlbumInfo> onAlbumSelected) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.onAlbumSelected = onAlbumSelected;
        this.holder = new BoxHolder<>();
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
//        this.onShow(this::refresh);
//        this.onRealize(this::refresh);
        this.append(holder);
    }

    public synchronized ArtistInfoLoader setArtistId(String artistId) {
        if (artistId.equals(this.artistId.get())) {
            return this;
        }

        doLoad(artistId);
        return this;
    }

    private void doLoad(String artistId) {
        this.artistId.set(artistId);
        var future = CompletableFuture.supplyAsync(() -> this.client.useClient(client -> client.getArtistInfo(artistId)));
        // just make sure we didnt change artist in the meantime we were loading data:
        if (!artistId.equals(this.artistId.get())) {
            return;
        }

        var loader = new FutureLoader<>(future, info -> new ArtistInfoFlowBox(
                this.client,
                info,
                this::onAlbumSelected
        ));
        this.holder.setChild(loader);
    }

    private void onAlbumSelected(ArtistAlbumInfo selected) {
        var handler = this.onAlbumSelected;
        if (handler == null) {
            return;
        }
        handler.accept(selected);
    }
}
