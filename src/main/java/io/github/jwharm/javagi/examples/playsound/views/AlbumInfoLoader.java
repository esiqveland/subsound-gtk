package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AlbumInfoLoader extends Box {
    private final ServerClient client;
    private final String albumId = "";
    private final AtomicReference<AlbumInfoBox> viewHolder = new AtomicReference<>();
    private final Function<SongInfo, CompletableFuture<SongCache.GetSongResult>> onSongSelected;
    private final ThumbnailCache thumbLoader;

    public AlbumInfoLoader(
            ThumbnailCache thumbLoader,
            ServerClient client,
            Function<SongInfo, CompletableFuture<SongCache.GetSongResult>> onSongSelected
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

    public synchronized AlbumInfoLoader setAlbumId(String albumId) {
        if (albumId.equals(this.albumId)) {
            return this;
        }

        doLoad(albumId);
        return this;
    }

    private void replaceArtistInfo(AlbumInfo info) {
        var current = viewHolder.get();
        Utils.runOnMainThread(() -> {
            if (current != null) {
                this.remove(current);
            }
            var next = new AlbumInfoBox(thumbLoader, info, this.onSongSelected::apply);
            this.viewHolder.set(next);
            this.append(next);
        });
    }

    private CompletableFuture<AlbumInfo> doLoad(String albumId) {
        return CompletableFuture.supplyAsync(() -> {
            var info = this.client.getAlbumInfo(albumId);

            // just make sure we didnt change artist in the meantime we were loading data:
            if (info.id().equals(albumId)) {
                this.replaceArtistInfo(info);
            }
            return info;
        });
    }
}
