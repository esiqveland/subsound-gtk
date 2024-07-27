package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import org.gnome.gtk.FlowBox;

public class AlbumsOverviewPage extends FlowBox {

    private final ThumbnailCache thumbLoader;
    private final ServerClient.AlbumInfo albumInfo;

    public AlbumsOverviewPage(ThumbnailCache thumbLoader, ServerClient.AlbumInfo albumInfo) {
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
    }
}
