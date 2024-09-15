package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ListStarred;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.LoadSongResult;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.BoxHolder;
import io.github.jwharm.javagi.examples.playsound.ui.components.FutureLoader;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class StarredLoader extends Box {
    private final ThumbnailCache thumbLoader;
    private final ServerClient client;
    private final Function<SongInfo, CompletableFuture<LoadSongResult>> onSongSelected;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;

    private final BoxHolder holder;

    public StarredLoader(
            ThumbnailCache thumbLoader,
            ServerClient client,
            Function<SongInfo, CompletableFuture<LoadSongResult>> onSongSelected,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.onSongSelected = onSongSelected;
        this.onAction = onAction;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);
        this.onShow(this::refresh);
        this.onRealize(this::refresh);
        this.holder = new BoxHolder();
        this.append(holder);
    }

    public synchronized StarredLoader refresh() {
        CompletableFuture<ListStarred> adsf = doLoad()
                .thenApply(listStarred -> {
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
                starred -> new StarredListView(starred, this.thumbLoader, this.onAction)
        );
        this.holder.setChild(loader);
        return this;
    }

    private CompletableFuture<ListStarred> doLoad() {
        return Utils.doAsync(this.client::getStarred);
    }
}
