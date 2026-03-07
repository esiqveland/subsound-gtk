package org.subsound.ui.views;

import org.gnome.gobject.GObject;
import org.gnome.gtk.Ordering;
import org.gnome.gtk.Sorter;
import org.jspecify.annotations.NonNull;
import org.subsound.app.state.PlaylistsStore.GPlaylist;

import java.util.Comparator;

public class GPlaylistSorter extends Sorter {
    private final Comparator<GPlaylist> comparator;

    public GPlaylistSorter(Comparator<GPlaylist> comparator) {
        super();
        this.comparator = comparator;
    }

    @Override
    public @NonNull Ordering compare(@NonNull GObject a, @NonNull GObject b) {
        if (a == b) {
            return Ordering.EQUAL;
        }
        if (a instanceof GPlaylist aa && b instanceof GPlaylist bb) {
            int compare = comparator.compare(aa, bb);
            if (compare == 0) {
                return Ordering.EQUAL;
            } else if (compare < 0) {
                return Ordering.SMALLER;
            } else {
                return Ordering.LARGER;
            }
        }
        return Ordering.EQUAL;
    }
}
