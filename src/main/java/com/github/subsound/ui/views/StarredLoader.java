package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient;
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

public class StarredLoader extends Box {
    private static final Logger log = LoggerFactory.getLogger(StarredLoader.class);
    private final ThumbnailCache thumbLoader;
    private final Consumer<AppNavigation.AppRoute> onNavigate;

    private final BoxHolder<FutureLoader<ListStarred, StarredListView>> holder;
    private final AppManager appManager;

    public StarredLoader(
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

    public synchronized StarredLoader refresh() {
        CompletableFuture<ListStarred> adsf = doLoad()
                .thenApply(listStarred -> {
                    log.info("StarredLoader hello {}", listStarred.songs().size());

                    int size = listStarred.songs().size();
                    int newSize = listStarred.songs().size() * 20;
                    var newList = new ArrayList<SongInfo>(newSize);
                    for (int i = 0; i < newSize; i++) {
                        int idx = i % size;
                        newList.add(listStarred.songs().get(idx));
                    }
                    return new ListStarred(newList);
                });
        var loader = new FutureLoader<>(
                adsf,
                starred -> new StarredListView(starred, this.thumbLoader, this.appManager, this.onNavigate)
        );
        this.holder.setChild(loader);
        return this;
    }

    private CompletableFuture<ListStarred> doLoad() {
        return Utils.doAsync(() -> this.appManager.useClient(ServerClient::getStarred));
    }
}
