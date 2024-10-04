package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ArtistInfo;
import com.github.subsound.integration.ServerClient.ListArtists;
import com.github.subsound.integration.ServerClient.ListStarred;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.FutureLoader;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ArtistListLoader extends Box {
    private static final Logger log = LoggerFactory.getLogger(ArtistListLoader.class);
    private final ThumbnailCache thumbLoader;
    private final Consumer<AppNavigation.AppRoute> onNavigate;

    private final BoxHolder<FutureLoader<ListArtists, ArtistsListBox>> holder;
    private final AppManager appManager;

    public ArtistListLoader(
            ThumbnailCache thumbLoader,
            AppManager appManager,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.onNavigate = onNavigate;
        this.holder = new BoxHolder<>();
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
        this.onShow(this::refresh);
        this.onRealize(this::refresh);
        this.append(holder);
    }

    public synchronized ArtistListLoader refresh() {
        var loadingFuture = doLoad()
                .thenApply(data -> {
                    log.info("ArtistListLoader hello size={}", data.list().size());
                    return data;
                });
        var loader = new FutureLoader<>(
                loadingFuture,
                artists -> new ArtistsListBox(
                        this.thumbLoader,
                        this.appManager,
                        artists.list(),
                        albumInfo -> this.onNavigate.accept(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()))
                )
        );
        this.holder.setChild(loader);
        return this;
    }

    private CompletableFuture<ListArtists> doLoad() {
        return Utils.doAsync(() -> this.appManager.useClient(ServerClient::getArtists));
    }
}
