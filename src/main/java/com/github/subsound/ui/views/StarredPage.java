package com.github.subsound.ui.views;

import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

public class StarredPage extends Box {

    public StarredPage() {
        this.setOrientation(Orientation.VERTICAL);
        this.setSpacing(0);
        this.setValign(Align.FILL);
        this.setHalign(Align.FILL);
        this.setVexpand(true);
        this.setHexpand(true);
    }
}
