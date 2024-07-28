package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ListStarred;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.LoadSongResult;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class StarredLoader extends Box {
    private final ServerClient client;
    private final ScrolledWindow window;
    private final Function<SongInfo, CompletableFuture<LoadSongResult>> onSongSelected;
    private final ThumbnailCache thumbLoader;

    public StarredLoader(
            ThumbnailCache thumbLoader,
            ServerClient client,
            Function<SongInfo, CompletableFuture<LoadSongResult>> onSongSelected
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.onSongSelected = onSongSelected;
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
        doLoad();
        return this;
    }

    private void replaceInfo(ListStarred info) {
        Utils.runOnMainThread(() -> {
            var old = this.window.getChild();
            if (old != null) {
                this.remove(old);
            }
            var next = new SongList(thumbLoader, info.songs(), this.onSongSelected::apply);
            this.window.setChild(next);
        });
    }

    private CompletableFuture<ListStarred> doLoad() {
        return CompletableFuture.supplyAsync(() -> {
            var info = this.client.getStarred();
            this.replaceInfo(info);
            return info;
        });
    }
}
