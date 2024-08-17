package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ListStarred;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.LoadSongResult;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.ui.components.FutureLoader;
import org.gnome.gtk.*;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class StarredLoader extends Box {
    private final ThumbnailCache thumbLoader;
    private final ServerClient client;
    private final Function<SongInfo, CompletableFuture<LoadSongResult>> onSongSelected;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;

    private final ScrolledWindow window;

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
        this.window = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .build();
        this.append(window);
    }

    public synchronized StarredLoader refresh() {
        CompletableFuture<ListStarred> adsf = doLoad();
        var loader = new FutureLoader<>(
                adsf,
                starred -> new SongList(thumbLoader, starred.songs(), this.onAction, this.onSongSelected::apply)
        );
        replaceWidget(loader);
        return this;
    }

    private void replaceWidget(Widget w) {
        Utils.runOnMainThread(() -> {
            var old = this.window.getChild();
            if (old != null) {
                this.remove(old);
            }
            this.window.setChild(w);
        });
    }

    private CompletableFuture<ListStarred> doLoad() {
        return Utils.doAsync(this.client::getStarred);
    }
}
