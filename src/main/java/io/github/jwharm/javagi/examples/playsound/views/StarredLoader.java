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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class StarredLoader extends Box {
    private final ServerClient client;
    private final AtomicReference<SongList> viewHolder = new AtomicReference<>();
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
    }

    public synchronized StarredLoader refresh() {
        doLoad();
        return this;
    }

    private void replaceInfo(ListStarred info) {
        var current = viewHolder.get();
        Utils.runOnMainThread(() -> {
            if (current != null) {
                this.remove(current);
            }
            var next = new SongList(thumbLoader, info.songs(), this.onSongSelected::apply);
            this.viewHolder.set(next);
            this.append(next);
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
