package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import org.gnome.gtk.FlowBox;

public class AlbumsOverviewPage extends FlowBox {

    private final ThumbLoader thumbLoader;
    private final ServerClient.AlbumInfo albumInfo;

    public AlbumsOverviewPage(ThumbLoader thumbLoader, ServerClient.AlbumInfo albumInfo) {
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
    }
}
