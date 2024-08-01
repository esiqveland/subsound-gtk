package io.github.jwharm.javagi.examples.playsound.views.components;

import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NowPlayingOverlayIcon extends Overlay {
    private static final Logger log = LoggerFactory.getLogger(NowPlayingOverlayIcon.class);

    private final Image icon;
    private final Widget child;

    public NowPlayingOverlayIcon(int size, Widget child) {
        super();
        this.icon = Image.fromIconName("media-playback-start-symbolic");
        this.child = child;
        icon.addCssClass("circular");
        icon.setSizeRequest(48, 48);
        this.addOverlay(icon);
        this.setSizeRequest(size, size);

        this.setHexpand(false);
        this.setVexpand(false);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
        this.addCssClass("rounded");
        this.setChild(this.child);
    }
}
