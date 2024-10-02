package com.github.subsound.ui.components;

import org.gnome.adw.Clamp;
import org.gnome.gtk.Widget;

public class MainViewClamp {
    public static final int maxSize = 900;

    public static Clamp create(Widget child) {
        return Clamp.builder().setMaximumSize(maxSize).setChild(child).build();
    }

}
