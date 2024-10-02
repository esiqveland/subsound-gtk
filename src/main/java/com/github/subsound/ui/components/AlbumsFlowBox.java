package com.github.subsound.ui.components;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.persistence.ThumbnailCache;
import org.gnome.gtk.Align;
import org.gnome.gtk.FlowBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.SelectionMode;

import java.util.List;
import java.util.function.Consumer;

public class AlbumsFlowBox extends FlowBox {
    private final ThumbnailCache thumbLoader;
    private final List<ServerClient.ArtistAlbumInfo> albumInfo;
    private final Consumer<ServerClient.ArtistAlbumInfo> onSelected;

    public AlbumsFlowBox(
            ThumbnailCache thumbLoader,
            List<ServerClient.ArtistAlbumInfo> albumInfo,
            Consumer<ServerClient.ArtistAlbumInfo> onSelected
    ) {
        super();
        //setCssClasses(Utils.cssClasses("navigation-sidebar"));
        setOrientation(Orientation.HORIZONTAL);
        setRowSpacing(24);
        setColumnSpacing(24);
        setHexpand(false);
        setVexpand(true);
        setHomogeneous(true);
        setHalign(Align.START);
        setValign(Align.START);
        setMinChildrenPerLine(1);
        setSensitive(true);
        setSelectionMode(SelectionMode.SINGLE);

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
