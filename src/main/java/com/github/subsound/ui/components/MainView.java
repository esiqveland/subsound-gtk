package com.github.subsound.ui.components;

import org.gnome.adw.Clamp;
import org.gnome.adw.HeaderBar;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;

public class MainView extends Box {
    private final Widget child;
    private final HeaderBar headerBar;

    public MainView(Widget child) {
        super(Orientation.VERTICAL, 0);
        this.child = child;
        this.headerBar = HeaderBar.builder().build();
        this.append(headerBar);
        this.append(child);
    }
}
