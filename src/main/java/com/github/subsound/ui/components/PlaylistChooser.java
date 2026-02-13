package com.github.subsound.ui.components;

import com.github.subsound.app.state.PlaylistsStore.GPlaylist;
import com.github.subsound.integration.ServerClient.PlaylistKind;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Box;
import org.gnome.gtk.CustomFilter;
import org.gnome.gtk.FilterListModel;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Separator;

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
    private final Box contentBox;
    private final FilterListModel<GPlaylist> normalPlaylists;

    public PlaylistChooser(
            ListStore<GPlaylist> playlistsStore,
            BiConsumer<String, String> onSelected
    ) {
        super();
        this.contentBox = new Box(VERTICAL, 2);
        this.playlistsStore = playlistsStore;
        this.onSelected = onSelected;
        // TODO: consider dropping this filter, and just making special rows for the special lists Starred + Downloaded
        this.normalPlaylists = new FilterListModel<>(
                this.playlistsStore,
                new CustomFilter(item -> {
                    if (item instanceof GPlaylist gPlaylist) {
                        return gPlaylist.getPlaylist().kind() == PlaylistKind.NORMAL;
                    }
                    return false;
                }
        ));

        this.listBox = new ListBox();
        this.listBox.setActivateOnSingleClick(true);
        this.listBox.addCssClass("navigation-sidebar");
        var signal = this.listBox.onRowActivated(row -> {
            this.popdown();
            var item = this.normalPlaylists.getItem(row.getIndex());
            this.onSelected.accept(item.getId(), item.getName());
        });

        this.listBox.bindModel(
                this.normalPlaylists,
                item -> {
                    if (item instanceof GPlaylist playlist) {
                        var row = new ListBoxRow();
                        var label1 = new Label(playlist.getName());
                        label1.setHalign(START);
                        label1.addCssClass("body");
                        row.setChild(label1);
                        return row;
                    } else {
                        return new Label("something is broken");
                    }
                }
        );

        var scrolledWindow = ScrolledWindow.builder()
                .setChild(listBox)
                .setMinContentHeight(200)
                .setMaxContentHeight(450)
                .setMinContentWidth(200)
                .setPropagateNaturalHeight(true)
                .build();

        var label = new Label("Add all to playlist:");
        label.setHalign(START);
        label.addCssClass("body");
        var box = new Box(VERTICAL, 2);
        box.setMarginStart(8);
        box.setMarginTop(4);
        box.setMarginBottom(4);
        box.setMarginEnd(8);
        box.append(label);

        this.contentBox.append(box);
        this.contentBox.append(new Separator(org.gnome.gtk.Orientation.HORIZONTAL));
        this.contentBox.append(scrolledWindow);

        this.setChild(this.contentBox);
        this.onDestroy(signal::disconnect);
    }

}
