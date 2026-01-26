package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ListPlaylists;
import com.github.subsound.integration.ServerClient.ListStarred;
import com.github.subsound.integration.ServerClient.PlaylistKind;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.FutureLoader;
import com.github.subsound.ui.components.PlaylistsListView;
import com.github.subsound.ui.views.StarredLoader.PlaylistsData;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.javagi.gobject.SignalConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PlaylistsViewLoader extends Box {
    private static final Logger log = LoggerFactory.getLogger(PlaylistsViewLoader.class);
    private final ThumbnailCache thumbLoader;
    private final Consumer<AppNavigation.AppRoute> onNavigate;
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);

    private final BoxHolder<FutureLoader<PlaylistsData, PlaylistsListView>> holder;
    private final AppManager appManager;

    public PlaylistsViewLoader(
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
        var signal = this.onMap(() -> {
            if (this.isLoaded.get()) {
                return;
            }
            refresh().thenAccept(_ -> this.isLoaded.set(true));
        });
        this.onDestroy(() -> signal.disconnect());

        this.append(holder);
    }

    public synchronized CompletableFuture<PlaylistsData> refresh() {
        var loadPlaylistList = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getPlaylists));
        var loadStarredList = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getStarred));

        var dataFuture = loadPlaylistList
                .thenCombine(loadStarredList, PlaylistsData::new)
                .thenApply(data -> {
                    var listStarred = data.starredList();
                    log.info("PlaylistsViewLoader hello {}", listStarred.songs().size());

                    int size = listStarred.songs().size();
                    int newSize = listStarred.songs().size() * 20;
                    var newList = new ArrayList<SongInfo>(newSize);
                    for (int i = 0; i < newSize; i++) {
                        int idx = i % size;
                        newList.add(listStarred.songs().get(idx));
                    }
                    return new PlaylistsData(data.playlistList(), new ListStarred(newList));
                })
                .thenApply(data -> {
                    var part1 = data.playlistList().playlists().stream();
                    ServerClient.PlaylistSimple starred = new ServerClient.PlaylistSimple(
                            "starred",
                            "Starred",
                            PlaylistKind.STARRED,
                            data.starredList().songs().stream().flatMap(s -> s.coverArt().stream()).findFirst(),
                            data.starredList().songs().size(),
                            data.starredList().songs().stream().flatMap(s -> s.starred().stream()).findFirst().orElseGet(Instant::now)
                    );
                    var downloadCount = this.appManager.getCompletedDownloads().size();
                    ServerClient.PlaylistSimple downloaded = new ServerClient.PlaylistSimple(
                            "downloaded",
                            "Downloaded",
                            PlaylistKind.DOWNLOADED,
                            Optional.empty(),
                            downloadCount,
                            Instant.now()
                    );
                    var playlists = Stream.of(starred, downloaded).collect(Collectors.toList());
                    part1.forEach(playlists::add);
                    return new PlaylistsData(new ListPlaylists(playlists), data.starredList());
                });
        var loader = new FutureLoader<>(
                dataFuture,
                starred -> new PlaylistsListView(appManager, starred)
        );
        this.holder.setChild(loader);
        return dataFuture;
    }
}
