package com.github.subsound.ui.views;

import com.github.subsound.ui.views.PlaylistListViewV2.GPlaylistEntry;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Ordering;
import org.gnome.gtk.Sorter;
import org.jspecify.annotations.NonNull;

import java.util.Comparator;

public class PlaylistEntrySorter extends Sorter {
    private final Comparator<GPlaylistEntry> comparator;

    public PlaylistEntrySorter(Comparator<GPlaylistEntry> comparator) {
        super();
        this.comparator = comparator;
    }

    @Override
    public @NonNull Ordering compare(@NonNull GObject a, @NonNull GObject b) {
        if (a == b) {
            return Ordering.EQUAL;
        }
        if (a instanceof GPlaylistEntry aa && b instanceof GPlaylistEntry bb) {
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
