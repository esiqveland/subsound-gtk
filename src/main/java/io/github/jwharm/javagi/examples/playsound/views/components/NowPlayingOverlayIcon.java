package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class NowPlayingOverlayIcon extends Overlay {
    private static final Logger log = LoggerFactory.getLogger(NowPlayingOverlayIcon.class);

    public enum NowPlayingState {
        NONE,
        PLAYING,
        PAUSED,
    }
    private final Image icon;
    private final Widget child;
    //private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private AtomicReference<NowPlayingState> state = new AtomicReference<>(NowPlayingState.NONE);
    private final AtomicBoolean isHover = new AtomicBoolean(false);

    public NowPlayingOverlayIcon(int size, Widget child) {
        this(size, child, NowPlayingState.NONE);
    }

    public NowPlayingOverlayIcon(int size, Widget child, NowPlayingState isPlaying) {
        super();
        this.child = child;
        this.state.set(isPlaying);
        this.icon = Image.fromIconName(Icons.PLAY.getIconName());
        this.icon.addCssClass("circular");
        //this.icon.addCssClass("accent");
        this.icon.setSizeRequest(size, size);
        this.icon.setVisible(this.state.get() != NowPlayingState.NONE);
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
                switch (this.state.get()) {
                    case PLAYING, PAUSED -> this.icon.setVisible(true);
                    case NONE -> this.icon.setVisible(false);
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

    public NowPlayingOverlayIcon setIsPlaying(NowPlayingState state) {
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
        Utils.runOnMainThread(() -> this.icon.setVisible(true));
    }

    private void hideOverlay() {
        Utils.runOnMainThread(() -> this.icon.setVisible(false));
    }
}
