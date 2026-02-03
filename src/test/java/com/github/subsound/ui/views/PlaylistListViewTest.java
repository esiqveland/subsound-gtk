package com.github.subsound.ui.views;

import org.gnome.gio.ListStore;
import org.gnome.gobject.GObject;
import org.junit.Test;

public class PlaylistListViewTest {

    @Test
    public void testClear() {
        var list = new ListStore<>(GObject.getType());
        // add a single item so its no longer empty:
        list.append(GObject.builder().build());
        //list.clear(); // throws UnsupportedOperationException
        list.removeAll(); // removeAll works
    }

}