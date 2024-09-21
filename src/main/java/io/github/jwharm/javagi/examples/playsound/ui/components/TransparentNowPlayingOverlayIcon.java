package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class TransparentNowPlayingOverlayIcon extends Overlay {
    private static final Logger log = LoggerFactory.getLogger(TransparentNowPlayingOverlayIcon.class);

    private final Box iconBox;
    private final Image icon;
    private final Widget child;
    private AtomicReference<NowPlayingState> state = new AtomicReference<>(NowPlayingState.NONE);
    private final AtomicBoolean isHover = new AtomicBoolean(false);

    public TransparentNowPlayingOverlayIcon(int size, Widget child) {
        this(size, child, NowPlayingState.NONE);
    }

    public TransparentNowPlayingOverlayIcon(int size, Widget child, NowPlayingState isPlaying) {
        super();
        this.child = child;
        this.state.set(isPlaying);
        this.iconBox = Box.builder()
                .setSpacing(0)
                .setOrientation(Orientation.VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .build();
        this.icon = Image.fromIconName(Icons.PLAY.getIconName());
        this.icon.addCssClass("circular");
        //this.icon.addCssClass("accent");
        //this.icon.setSizeRequest(size, size);
        this.icon.setHalign(Align.CENTER);
        this.icon.setValign(Align.CENTER);
        this.icon.setHexpand(true);
        this.icon.setVexpand(true);
        //this.icon.setVisible(this.state.get() != NowPlayingState.NONE);
        this.icon.addCssClass("success");
        this.icon.addCssClass("np-icon");
        //this.icon.addCssClass("now-playing-overlay-icon");
        this.iconBox.addCssClass("now-playing-overlay-icon");
        this.iconBox.append(icon);
        this.addOverlay(iconBox);
        this.setSizeRequest(size, size);

        this.setPlayingState(isPlaying);
        switch (this.state.get()) {
            case PLAYING, PAUSED -> this.showOverlay();
            case NONE -> this.hideOverlay();
        }

        this.setHexpand(false);
        this.setVexpand(false);
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
        this.addCssClass("now-playing-overlay");
        this.addCssClass("rounded");
        this.setChild(this.child);
    }

    public TransparentNowPlayingOverlayIcon setIsHover(boolean isHover) {
        this.isHover.set(isHover);

        Utils.runOnMainThread(() -> {
            if (this.isHover.get()) {
                this.showOverlay();
            } else {
                switch (this.state.get()) {
                    case PLAYING, PAUSED -> this.showOverlay();
                    case NONE -> this.hideOverlay();
                }
            }

            switch (this.state.get()) {
                case PLAYING, PAUSED -> {
                    if (!this.icon.hasCssClass("success")) {
                        this.icon.addCssClass("success");
                    }
                }
                case NONE -> {
                    if (this.icon.hasCssClass("success")) {
                        this.icon.removeCssClass("success");
                    }
                }
            }
        });
        return this;
    }

    public TransparentNowPlayingOverlayIcon setPlayingState(NowPlayingState state) {
        this.state.set(state);
        switch (this.state.get()) {
            case PAUSED -> {
                this.icon.setFromIconName(Icons.PAUSE.getIconName());
                this.showOverlay();
            }
            case PLAYING -> {
                this.icon.setFromIconName(Icons.PLAY.getIconName());
                this.showOverlay();
            }
            case NONE -> this.hideOverlay();
        }
        return this;
    }

    private void showOverlay() {
        Utils.runOnMainThread(() -> {
            this.iconBox.setVisible(true);
            this.child.setVisible(true);
        });
    }

    private void hideOverlay() {
        Utils.runOnMainThread(() -> {
            this.iconBox.setVisible(false);
            this.child.setVisible(true);
        });
    }
}
