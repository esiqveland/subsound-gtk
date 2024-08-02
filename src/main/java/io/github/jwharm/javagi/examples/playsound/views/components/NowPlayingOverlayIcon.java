package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class NowPlayingOverlayIcon extends Overlay {
    private static final Logger log = LoggerFactory.getLogger(NowPlayingOverlayIcon.class);

    private final Image icon;
    private final Widget child;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isHover = new AtomicBoolean(false);

    public NowPlayingOverlayIcon(int size, Widget child) {
        this(size, child, false);
    }

    public NowPlayingOverlayIcon(int size, Widget child, boolean isPlaying) {
        super();
        this.child = child;
        this.isPlaying.set(isPlaying);
        this.icon = Image.fromIconName("media-playback-start-symbolic");
        this.icon.addCssClass("circular");
        //this.icon.addCssClass("accent");
        this.icon.setSizeRequest(size, size);
        this.icon.setVisible(this.isPlaying.get());
        this.icon.addCssClass("success");
        this.addOverlay(icon);
        this.setSizeRequest(size, size);

        this.setHexpand(false);
        this.setVexpand(false);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
        this.addCssClass("now-playing-overlay");
        this.addCssClass("rounded");
        this.setChild(this.child);
    }

    public NowPlayingOverlayIcon setIsHover(boolean isHover) {
        this.isHover.set(isHover);

        Utils.runOnMainThread(() -> {
            if (this.isHover.get()) {
                this.icon.setVisible(true);
            } else {
                if (this.isPlaying.get()) {
                    this.icon.setVisible(true);
                } else {
                    this.icon.setVisible(false);
                }
            }
            if (this.isPlaying.get()) {
                if (!this.icon.hasCssClass("success")) {
                    this.icon.addCssClass("success");
                }
            } else {
                if (this.icon.hasCssClass("success")) {
                    this.icon.removeCssClass("success");
                }
            }
        });
        return this;
    }

    public NowPlayingOverlayIcon setIsPlaying(boolean isPlaying) {
        this.isPlaying.set(isPlaying);
        if (this.isPlaying.get()) {
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
