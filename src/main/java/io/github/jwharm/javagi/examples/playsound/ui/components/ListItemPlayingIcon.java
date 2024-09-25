package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overflow;

import java.util.concurrent.atomic.AtomicReference;

public class ListItemPlayingIcon extends Box {
    private final AtomicReference<NowPlayingState> state = new AtomicReference<>(NowPlayingState.NONE);
    private final Image icon;

    public ListItemPlayingIcon() {
        this(NowPlayingState.NONE, 32);
    }

    public ListItemPlayingIcon(NowPlayingState isPlaying, int size) {
        super(Orientation.HORIZONTAL, 0);
        this.setName("ListItemPlayingIcon");
        this.addCssClass("ListItemPlayingIcon");
        this.setHalign(Align.CENTER);
        this.setValign(Align.CENTER);
        this.setOverflow(Overflow.HIDDEN);
        //this.addCssClass("success");
        //this.addCssClass("accent");
        this.icon = Image.fromIconName(Icons.PLAY.getIconName());
        this.icon.addCssClass("circular");
        this.icon.setSizeRequest(size, size);
        //this.icon.setVisible(this.state.get() != NowPlayingState.NONE);
        this.append(this.icon);
    }

    public void setPlayingState(NowPlayingState state) {
        this.state.set(state);
        switch (this.state.get()) {
            case LOADING, PAUSED -> {
                this.icon.setFromIconName(Icons.PAUSE.getIconName());
                this.addCssClass(Classes.colorAccent.className());
            }
            case PLAYING -> {
                this.icon.setFromIconName(Icons.PLAY.getIconName());
                this.addCssClass(Classes.colorAccent.className());
            }
            case NONE ->{
                this.removeCssClass(Classes.colorAccent.className());
                this.icon.setFromIconName(Icons.PLAY.getIconName());
            }
        }
    }
}
