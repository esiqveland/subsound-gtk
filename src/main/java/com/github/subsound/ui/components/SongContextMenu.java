package com.github.subsound.ui.components;

import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.gnome.adw.SplitButton;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Popover;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.gnome.gtk.Orientation.VERTICAL;

public class SongContextMenu extends Popover {
    private final Popover menuPopover;
    private Box menuContent;
    private Consumer<SongContextAction> onContextAction;

    public record SimplePlaylist(PlaylistIdentifier id, String name) {}
    private Supplier<List<SimplePlaylist>> playlists;

    public sealed interface SongContextAction {
        record Action(PlayerAction action) implements SongContextAction {}
        record Play() implements SongContextAction {}
        record PlayNext() implements SongContextAction {}
        record PlayLast() implements SongContextAction {}
        record Star() implements SongContextAction {}
        record Unstar() implements SongContextAction {}
        record AddToDownload() implements SongContextAction {}
        record AddToPlaylist(PlaylistIdentifier id) implements SongContextAction {}
    }

    public SongContextMenu(Consumer<SongContextAction> onAction) {
        super();
        this.onContextAction = onAction;
        this.menuPopover = this;
        // Context menu popover
        this.menuContent = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(2)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var playMenuItem = menuItem("Play");
        playMenuItem.onClicked(() -> {
            menuPopover.popdown();
            this.onContextAction.accept(new SongContextAction.Play());
        });

        var playNextMenuItem = menuItem("Play Next");
        playNextMenuItem.onClicked(() -> {
            menuPopover.popdown();
            this.onContextAction.accept(new SongContextAction.PlayNext());
        });

        var addToQueueMenuItem = menuItem("Add to Queue");
        addToQueueMenuItem.onClicked(() -> {
            menuPopover.popdown();
            this.onContextAction.accept(new SongContextAction.PlayLast());
        });

//        var favoriteMenuItem = menuItem(songInfo.isStarred() ? "Unfavorite" : "Favorite");
//        favoriteMenuItem.onClicked(() -> {
//            menuPopover.popdown();
//            var action = songInfo.isStarred()
//                    ? new PlayerAction.Unstar(songInfo)
//                    : new PlayerAction.Star(songInfo);
//            this.onAction.apply(action);
//        });
        var favoriteMenuItem = menuItem("Add to Favorite");
        favoriteMenuItem.onClicked(() -> {
            menuPopover.popdown();
            this.onContextAction.accept(new SongContextAction.Star());
        });

        // TODO: need to open submenu with a list of playlists
        var addToPlaylistMenuItem = menuItem("Add to Playlist\u2026");
        addToPlaylistMenuItem.setTooltipText("TODO");
        addToPlaylistMenuItem.setSensitive(false);
        addToPlaylistMenuItem.onClicked(() -> {
            menuPopover.popdown();
            // TODO: read the latest list of playlists from the AppManager,
            // then present them as items in a submenu

            //this.onContextAction.apply(new SongContextAction.AddToPlaylist(id));
        });

        var downloadMenuItem = menuItem("Download");
        downloadMenuItem.onClicked(() -> {
            menuPopover.popdown();
            this.onContextAction.accept(new SongContextAction.AddToDownload());
        });

        menuContent.append(playMenuItem);
        menuContent.append(playNextMenuItem);
        menuContent.append(addToQueueMenuItem);
        menuContent.append(favoriteMenuItem);
        menuContent.append(addToPlaylistMenuItem);
        menuContent.append(downloadMenuItem);
        this.setChild(menuContent);
    }

    private static Button menuItem(String label) {
        var button = Button.builder().setLabel(label).build();
        button.addCssClass("flat");
        if (button.getChild() instanceof Label child) {
            child.setHalign(Align.START);
            child.addCssClass("body");
        }
        return button;
    }

}
