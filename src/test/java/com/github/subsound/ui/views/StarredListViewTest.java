package com.github.subsound.ui.views;

import org.gnome.gio.ListStore;
import org.gnome.gobject.GObject;
import org.junit.Test;

public class StarredListViewTest {

    @Test
    public void testClear() {
        var list = new ListStore<>(GObject.getType());
        // add a single item so its no longer empty:
        list.add(GObject.builder().build());
        //list.clear(); // throws UnsupportedOperationException
        list.removeAll(); // removeAll works
    }

}