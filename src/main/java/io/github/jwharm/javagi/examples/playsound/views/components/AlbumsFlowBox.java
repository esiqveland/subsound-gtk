package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbLoader;
import org.gnome.gtk.Align;
import org.gnome.gtk.FlowBox;

import java.util.List;
import java.util.function.Consumer;

public class AlbumsFlowBox extends FlowBox {
    private final ThumbLoader thumbLoader;
    private final List<ServerClient.ArtistAlbumInfo> albumInfo;
    private final Consumer<ServerClient.ArtistAlbumInfo> onSelected;

    public AlbumsFlowBox(
            ThumbLoader thumbLoader,
            List<ServerClient.ArtistAlbumInfo> albumInfo,
            Consumer<ServerClient.ArtistAlbumInfo> onSelected
    ) {
        super();
        setHexpand(true);
        setVexpand(true);
        setHalign(Align.CENTER);
        setValign(Align.START);

        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.onSelected = onSelected;

        this.onChildActivated((child) -> {
            var album = this.albumInfo.get(child.getIndex());
            this.onSelected.accept(album);
        });

        for (var album : albumInfo) {
            var widget = new AlbumFlowBoxChild(
                    this.thumbLoader,
                    album
            );
            this.append(widget);
        }
    }
}
