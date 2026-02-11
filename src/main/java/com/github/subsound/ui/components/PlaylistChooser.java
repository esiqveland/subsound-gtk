package com.github.subsound.ui.components;

import com.github.subsound.app.state.PlaylistsStore.GPlaylist;
import com.github.subsound.integration.ServerClient.PlaylistKind;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Widget;

import java.util.function.BiConsumer;

import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.VERTICAL;

/**
 * A popover that shows a scrollable list of playlists for the user to pick from.
 * Refreshes the playlist entries each time the popover is shown.
 */
public class PlaylistChooser extends Popover {
    private final ListStore<GPlaylist> playlistsStore;
    private final BiConsumer<String, String> onSelected;
    private final ListBox listBox;

    public PlaylistChooser(
            ListStore<GPlaylist> playlistsStore,
            BiConsumer<String, String> onSelected
    ) {
        super();
        this.playlistsStore = playlistsStore;
        this.onSelected = onSelected;

        this.listBox = ListBox.builder()
                .setActivateOnSingleClick(true)
                .build();
        listBox.addCssClass("navigation-sidebar");

        var scrolledWindow = ScrolledWindow.builder()
                .setChild(listBox)
                .setMinContentHeight(100)
                .setMaxContentHeight(300)
                .setMinContentWidth(200)
                .setPropagateNaturalHeight(true)
                .build();

        this.setChild(scrolledWindow);
        this.onMap(this::refreshEntries);
    }

    private void refreshEntries() {
        // Remove all existing rows
        Widget child;
        while ((child = listBox.getFirstChild()) != null) {
            listBox.remove(child);
        }

        for (int i = 0; i < playlistsStore.getNItems(); i++) {
            var gPlaylist = playlistsStore.getItem(i);
            if (gPlaylist.getPlaylist().kind() != PlaylistKind.NORMAL) {
                continue;
            }
            String playlistId = gPlaylist.getId();
            String playlistName = gPlaylist.getName();
            var btn = Button.builder().setLabel(playlistName).build();
            btn.addCssClass("flat");
            if (btn.getChild() instanceof Label label) {
                label.setHalign(START);
                label.addCssClass("body");
            }
            btn.onClicked(() -> {
                this.popdown();
                onSelected.accept(playlistId, playlistName);
            });
            var row = Box.builder().setOrientation(VERTICAL).build();
            row.append(btn);
            listBox.append(row);
        }
    }
}
