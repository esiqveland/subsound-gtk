package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NowPlayingOverlayIcon extends Overlay {
    private static final Logger log = LoggerFactory.getLogger(NowPlayingOverlayIcon.class);

    private final Image icon;
    private final Widget child;

    public NowPlayingOverlayIcon(int size, Widget child) {
        this(size, child, false);
    }

    public NowPlayingOverlayIcon(int size, Widget child, boolean isPlaying) {
        super();
        this.icon = Image.fromIconName("media-playback-start-symbolic");
        this.child = child;
        this.icon.addCssClass("circular");
        this.icon.addCssClass("now-playing-overlay");
        //this.icon.addCssClass("accent");
        this.icon.addCssClass("success");
        this.icon.setSizeRequest(48, 48);
        this.icon.setVisible(isPlaying);
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

    public NowPlayingOverlayIcon setIsPlaying(boolean isPlaying) {
        if (isPlaying) {
            this.showOverlay();
        } else {
            this.hideOverlay();
        }
        return this;
    }

    private void showOverlay() {
        Utils.runOnMainThread(() -> this.icon.setVisible(true));
    }
    private void hideOverlay() {
        Utils.runOnMainThread(() -> this.icon.setVisible(false));
    }
}
