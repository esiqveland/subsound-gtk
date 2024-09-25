package io.github.jwharm.javagi.examples.playsound.ui.components;

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
        LOADING,
    }
    private final ListItemPlayingIcon icon;
    private final Widget child;
    //private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicReference<NowPlayingState> state = new AtomicReference<>(NowPlayingState.NONE);
    private final AtomicBoolean isHover = new AtomicBoolean(false);

    public NowPlayingOverlayIcon(int size, Widget child) {
        this(size, child, NowPlayingState.NONE);
    }

    public NowPlayingOverlayIcon(int size, Widget child, NowPlayingState isPlaying) {
        super();
        this.child = child;
        this.state.set(isPlaying);
        this.icon = new ListItemPlayingIcon(NowPlayingState.NONE, size);

        this.addOverlay(icon);
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

    public void setIsHover(boolean isHover) {
        this.isHover.set(isHover);

        Utils.runOnMainThread(() -> {
            if (this.isHover.get()) {
                this.showOverlay();
            } else {
                switch (this.state.get()) {
                    case PLAYING, PAUSED, LOADING -> this.showOverlay();
                    case NONE -> this.hideOverlay();
                }
            }

            switch (this.state.get()) {
                case PLAYING, PAUSED, LOADING -> {
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
    }

    public void setPlayingState(NowPlayingState state) {
        this.state.set(state);
        this.icon.setPlayingState(state);
        switch (this.state.get()) {
            case PAUSED, PLAYING -> this.showOverlay();
            case NONE -> this.hideOverlay();
        }
    }

    private void showOverlay() {
        Utils.runOnMainThread(() -> {
            this.icon.setVisible(true);
            this.child.setVisible(false);
        });
    }

    private void hideOverlay() {
        Utils.runOnMainThread(() -> {
            this.icon.setVisible(false);
            this.child.setVisible(true);
        });
    }
}
