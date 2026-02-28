package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ObjectIdentifier;
import com.github.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.ui.components.AdwDialogHelper;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.AppNavigation.AppRoute;
import com.github.subsound.ui.components.Classes;
import com.github.subsound.ui.components.ClickLabel;
import com.github.subsound.ui.components.Icons;
import com.github.subsound.ui.components.ListItemPlayingIcon;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.RoundedAlbumArt;
import com.github.subsound.ui.components.StarButton;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.adw.AlertDialog;
import org.gnome.gio.ListStore;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.ColumnViewColumn;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Popover;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.SortListModel;
import org.javagi.gobject.SignalConnection;
import org.javagi.gobject.types.Types;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.subsound.ui.components.AdwDialogHelper.CANCEL_LABEL_ID;
import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static org.gnome.adw.ResponseAppearance.DEFAULT;
import static org.gnome.adw.ResponseAppearance.DESTRUCTIVE;
import static org.gnome.adw.ResponseAppearance.SUGGESTED;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class PlaylistListViewV2 extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(PlaylistListViewV2.class);

    private final AppManager appManager;
    private final ColumnView listView;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ScrolledWindow scroll;
    private final Label titleLabel;
    private final MenuButton menuButton;
    private final AtomicReference<MiniState> prevState;
    private final Consumer<AppRoute> onNavigate;
    private final ListStore<GPlaylistEntry> listModel = new ListStore<>();
    private final SortListModel<GPlaylistEntry> sortModel;
    private final SingleSelection<GPlaylistEntry> selectionModel;
    private final ConcurrentHashMap<String, List<NowPlayingCell>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TitleArtistCell>> listenersTitle = new ConcurrentHashMap<>();
    private final AtomicReference<ServerClient.PlaylistSimple> currentPlaylist = new AtomicReference<>();
    private volatile boolean reloadNeeded = false;
    private volatile int lastKnownSongCount = 0;
    @Nullable private SignalConnection<?> playlistNotifySignal = null;

    /**
     * Wrapper GObject that pairs a GSongInfo with its original playlist position,
     * enabling a "revert to playlist order" sort without a side-table.
     */
    public static class GPlaylistEntry extends GObject {
        public static final Type gtype = Types.register(GPlaylistEntry.class);

        private GSongInfo gSong;
        private int position;

        public GPlaylistEntry(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        public static GPlaylistEntry of(GSongInfo gSong, int position) {
            var instance = (GPlaylistEntry) GObject.newInstance(getType());
            instance.gSong = gSong;
            instance.position = position;
            return instance;
        }

        public GSongInfo gSong() {
            return gSong;
        }

        public SongInfo info() {
            return gSong.getSongInfo();
        }

        public int position() {
            return position;
        }

        public void setPosition(int p) {
            this.position = p;
        }
    }

    public PlaylistListViewV2(
            AppManager appManager,
            Consumer<AppRoute> onNavigate
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.onAction = appManager::handleAction;
        this.prevState = new AtomicReference<>(selectState(null, appManager.getState()));
        this.onNavigate = onNavigate;
        this.setHalign(CENTER);
        this.setValign(START);
        this.setHexpand(true);
        this.setVexpand(true);

        // 1. Build ColumnView without model (model set after columns + sorter are wired)
        this.listView = ColumnView.builder()
                .setShowRowSeparators(false)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .build();

        // 2. Build columns with per-column factories and sorters, then append them

        // --- Now-playing / track-number column (60px, sortable by original position) ---
        var nowPlayingFactory = new SignalListItemFactory();
        nowPlayingFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new NowPlayingCell();
            listItem.setChild(cell);
        });
        nowPlayingFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
                cell.bind(entry.gSong(), listItem, prevState.get());
                var list = listeners.computeIfAbsent(entry.gSong.getId(), key -> new ArrayList<>());
                list.add(cell);
            }
        });
        nowPlayingFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
                var gSong = cell.gSong;
                cell.unbind();
                listeners.get(gSong.getId()).remove(cell);
            }
        });
        nowPlayingFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
            }
            listItem.setChild(null);
        });

        var orderSorter = new PlaylistEntrySorter(Comparator.comparingInt(GPlaylistEntry::position));
        var nowPlayingCol = new ColumnViewColumn("#", nowPlayingFactory);
        nowPlayingCol.setFixedWidth(60);
        nowPlayingCol.setSorter(orderSorter);
        this.listView.appendColumn(nowPlayingCol);

        // --- Album art column (44px, no sorter) ---
        var artFactory = new SignalListItemFactory();
        artFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new AlbumArtCell(appManager));
        });
        artFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof AlbumArtCell cell) {
                cell.bind(entry.gSong());
            }
        });
        artFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });
        var artCol = new ColumnViewColumn("", artFactory);
        artCol.setFixedWidth(60);
        this.listView.appendColumn(artCol);

        // --- Title column (expand, sortable by title) ---
        var titleFactory = new SignalListItemFactory();
        titleFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new TitleArtistCell(this.onNavigate);
            listItem.setChild(cell);
        });
        titleFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof TitleArtistCell cell) {
                cell.bind(entry.gSong(), listItem);
                var list = listenersTitle.computeIfAbsent(entry.gSong.getId(), key -> new ArrayList<>());
                list.add(cell);
            }
        });
        titleFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof TitleArtistCell cell) {
                var gSong = cell.gSong;
                cell.unbind();
                listenersTitle.get(gSong.getId()).remove(cell);
            }
        });
        titleFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var titleSorter = new PlaylistEntrySorter((a, b) -> {
            return a.gSong().getTitle().compareToIgnoreCase(b.gSong().getTitle());
        });
        var titleCol = new ColumnViewColumn("Title", titleFactory);
        titleCol.setExpand(true);
        titleCol.setSorter(titleSorter);
        this.listView.appendColumn(titleCol);

        // --- Album column (200px, sortable by album) ---
        var albumFactory = new SignalListItemFactory();
        albumFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new AlbumCell(this.onNavigate));
        });
        albumFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof AlbumCell cell) {
                cell.bind(entry.gSong());
            }
        });
        albumFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var albumSorter = new PlaylistEntrySorter((a, b) -> {
            var albumA = a.info().album();
            var albumB = b.info().album();
            albumA = albumA == null ? "" : albumA;
            albumB = albumB == null ? "" : albumB;
            return albumA.compareToIgnoreCase(albumB);
        });
        var albumCol = new ColumnViewColumn("Album", albumFactory);
        albumCol.setFixedWidth(200);
        albumCol.setSorter(albumSorter);
        this.listView.appendColumn(albumCol);

        // --- Duration column (80px, sortable by duration millis) ---
        var durationFactory = new SignalListItemFactory();
        durationFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var label = infoLabel("", Classes.labelDim.add(Classes.labelNumeric));
            label.setHalign(END);
            label.setMarginEnd(8);
            listItem.setChild(label);
        });
        durationFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof Label label) {
                label.setLabel(Utils.formatDurationShort(entry.info().duration()));
            }
        });
        durationFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var durationSorter = new PlaylistEntrySorter(Comparator.comparingLong(a -> a.info().duration().toMillis()));
        var durationCol = new ColumnViewColumn("Duration", durationFactory);
        durationCol.setFixedWidth(80);
        durationCol.setSorter(durationSorter);
        this.listView.appendColumn(durationCol);

        // --- Star column (48px, sortable by starred presence) ---
        var starFactory = new SignalListItemFactory();
        starFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new StarCell(this.onAction));
        });
        starFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof StarCell cell) {
                cell.bind(entry.gSong());
            }
        });
        starFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof StarCell cell) {
                cell.unbind();
            }
        });
        starFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var starSorter = new PlaylistEntrySorter((a, b) -> {
            var sa = a.info().starred().isPresent();
            var sb = b.info().starred().isPresent();
            return Boolean.compare(sb, sa);
        });
        var starCol = new ColumnViewColumn("★", starFactory);
        starCol.setFixedWidth(48);
        starCol.setSorter(starSorter);
        this.listView.appendColumn(starCol);

        // 3. Wire sorting: ColumnView aggregates per-column sorters into one composite sorter
        this.sortModel = new SortListModel<>(this.listModel, this.listView.getSorter());

        // 4. Selection wraps sort model
        this.selectionModel = new SingleSelection<>(this.sortModel);
        this.listView.setModel(this.selectionModel);

        // Activate: play in sorted order so next/prev respect current sort
        var activateSignal = this.listView.onActivate(index -> {
            var entry = this.sortModel.getItem(index);
            if (entry == null) {
                return;
            }
            log.info("listView.onActivate: {} {}", index, entry.info().title());
            List<SongInfo> songs = this.sortModel.stream().map(GPlaylistEntry::info).toList();
            this.onAction.apply(new PlayerAction.PlayAndReplaceQueue(
                    new PlaylistIdentifier(this.currentPlaylist.get().id()),
                    songs,
                    index
            ));
        });

        var keyController = new EventControllerKey();
        keyController.onKeyPressed((keyval, keycode, state) -> {
            if (keyval != 0xFFFF) {
                return false; // GDK_KEY_Delete
            }
            var playlist = currentPlaylist.get();
            if (playlist == null || playlist.kind() != ServerClient.PlaylistKind.NORMAL) {
                return false;
            }
            var entry = selectionModel.getSelectedItem();
            if (entry == null) {
                return false;
            }
            int deletedPos = entry.position();
            AdwDialogHelper.ofDialog(
                    PlaylistListViewV2.this,
                    "Remove from playlist",
                    "Remove \"%s\" from \"%s\"?".formatted(entry.info().title(), playlist.name()),
                    List.of(
                            new AdwDialogHelper.Response(CANCEL_LABEL_ID, "_Cancel", DEFAULT),
                            new AdwDialogHelper.Response("delete", "_Remove", DESTRUCTIVE)
                    )
            ).thenAccept(result -> {
                if (!"delete".equals(result.label())) {
                    return;
                }
                onAction.apply(new PlayerAction.RemoveFromPlaylist(
                        entry.info(), deletedPos, playlist.id(), playlist.name()));
                Utils.runOnMainThread(() -> {
                    for (int i = 0; i < listModel.getNItems(); i++) {
                        if (listModel.getItem(i) == entry) {
                            listModel.remove(i);
                            break;
                        }
                    }
                    for (int i = 0; i < listModel.getNItems(); i++) {
                        var e = listModel.getItem(i);
                        if (e.position() > deletedPos) {
                            e.setPosition(e.position() - 1);
                        }
                    }
                });
            });
            return true;
        });
        this.listView.addController(keyController);

        var mapSignal = this.onMap(() -> {
            appManager.addOnStateChanged(this);

            var playlist = this.currentPlaylist.get();
            if (reloadNeeded && playlist != null && playlist.kind() == ServerClient.PlaylistKind.NORMAL) {
                reloadNeeded = false;
                var songStore = appManager.getSongStore();
                Utils.doAsync(() -> {
                    var songs = appManager.useClient(cl -> cl.getPlaylist(playlist.id()).songs())
                            .stream().map(songStore::newInstance).toList();
                    setSongs(songs, playlist);
                });
            }
        });
        var unmapSignal = this.onUnmap(() -> appManager.removeOnStateChanged(this));
        this.onDestroy(() -> {
            log.info("PlaylistListViewV2: onDestroy");
            appManager.removeOnStateChanged(this);
            if (playlistNotifySignal != null) {
                playlistNotifySignal.disconnect();
                playlistNotifySignal = null;
            }
            mapSignal.disconnect();
            unmapSignal.disconnect();
            activateSignal.disconnect();
        });

        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .build();
        this.scroll.setChild(this.listView);

        this.titleLabel = Label.builder()
                .setLabel("")
                .setHalign(START)
                .setHexpand(true)
                .setCssClasses(new String[]{"title-2"})
                .build();

        this.menuButton = MenuButton.builder()
                .setIconName(Icons.OpenMenu.getIconName())
                .setPopover(buildMenuPopover())
                .setValign(Align.CENTER)
                .setVisible(false)
                .build();
        this.menuButton.addCssClass("flat");
        this.menuButton.addCssClass("circular");

        var headerBox = new Box(HORIZONTAL, 0);
        headerBox.setMarginTop(12);
        headerBox.setMarginBottom(8);
        headerBox.setMarginStart(16);
        headerBox.setMarginEnd(8);
        headerBox.append(this.titleLabel);
        headerBox.append(this.menuButton);

        this.append(headerBox);
        this.append(this.scroll);
    }

    public void setSongs(List<GSongInfo> songs, ServerClient.PlaylistSimple playlist) {
        this.currentPlaylist.set(playlist);
        this.lastKnownSongCount = playlist.songCount();
        this.reloadNeeded = false;
        var items = new GPlaylistEntry[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            items[i] = GPlaylistEntry.of(songs.get(i), i);
        }
        Utils.runOnMainThread(() -> {
            this.titleLabel.setLabel(playlist.name());
            this.menuButton.setVisible(playlist.kind() == ServerClient.PlaylistKind.NORMAL);
            this.listModel.removeAll();
            this.listModel.splice(0, 0, items);
            // Subscribe to GPlaylist metadata changes for the current playlist
            if (this.playlistNotifySignal != null) {
                this.playlistNotifySignal.disconnect();
                this.playlistNotifySignal = null;
            }

            if (playlist.kind() == ServerClient.PlaylistKind.NORMAL) {
                var store = appManager.getPlaylistsListStore();
                for (int i = 0; i < store.getNItems(); i++) {
                    var gp = store.getItem(i);
                    if (playlist.id().equals(gp.getId())) {
                        this.playlistNotifySignal = gp.onNotify("name", _ -> {
                            if (gp.getPlaylist().songCount() != this.lastKnownSongCount) {
                                this.reloadNeeded = true;
                            }
                        });
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var prev = prevState.get();
        var next = selectState(prev, state);
        if (next == prev) {
            return;
        }
        this.prevState.set(next);
        log.info("PlaylistListViewV2: onStateChanged: {} -> {}", prev, next);
        var prevSongId = prev.songInfo().map(SongInfo::id).orElse("");
        var nextSongId = next.songInfo().map(SongInfo::id).orElse("");
        int prevPos = prev.position().orElse(-1);
        int nextPos = next.position().orElse(-1);
        String playingContextId = next.playContext.map(ObjectIdentifier::getId).orElse("");
        boolean isCurrentPlayContext = this.currentPlaylist.get().id().equals(playingContextId);

        // TODO: currentlyPlayingPosition is not sufficient, we also need to know which 'context'
        // currentlyPlayingPosition is only the position in the current playQueue,
        // so could be a position in a shuffled list, or a shuffled list with user-queued additional tracks in it
        int currentlyPlayingPosition = next.position().orElse(-1);

        var playingState = next.nowPlayingState();
        if (!prevSongId.equals(nextSongId) || nextPos != prevPos) {
            var from = this.listeners.get(prevSongId);
            if (from != null) {
                for (var l : from) {
                    l.updateCellIsPlaying(false, NowPlayingState.NONE);
                }
            }
            var to = this.listeners.get(nextSongId);
            if (to != null) {
                for (var l : to) {
                    int boundPosition = l.listPosition;
                    boolean isCurrentlyPlayingThisPosition = boundPosition == currentlyPlayingPosition && isCurrentPlayContext;
                    l.updateCellIsPlaying(isCurrentlyPlayingThisPosition, playingState);
                }
            }
            var from1 = this.listenersTitle.get(prevSongId);
            if (from1 != null) {
                for (var l : from1) {
                    l.updateRow(false);
                }
            }
            var to1 = this.listenersTitle.get(nextSongId);
            if (to1 != null) {
                for (var l : to1) {
                    int boundPosition = l.listPosition;
                    boolean isCurrentlyPlayingThisPosition = boundPosition == currentlyPlayingPosition && isCurrentPlayContext;
                    l.updateRow(isCurrentlyPlayingThisPosition);
                }
            }
        }
        if (prev.nowPlayingState() != next.nowPlayingState()) {
            var to = this.listeners.get(nextSongId);
            if (to != null) {
                for (var l : to) {
                    l.update(next);
                }
            }
        }
    }

    public record MiniState(
            Optional<SongInfo> songInfo,
            NowPlayingState nowPlayingState,
            Optional<ObjectIdentifier> playContext,
            Optional<Integer> position
    ){}

    private MiniState selectState(@Nullable MiniState prev, AppManager.AppState state) {
        var npSong = state.nowPlaying().map(AppManager.NowPlaying::song);
        var nowPlayingState = getNowPlayingState(state.player().state());
        if (prev == null) {
            return new MiniState(npSong, nowPlayingState, state.queue().playContext(), state.queue().position());
        }
        var prevSongId = prev.songInfo().map(SongInfo::id).orElse("");
        var nextSongId = npSong.map(SongInfo::id).orElse("");
        var playContext = state.queue().playContext();
        var pos = state.queue().position();
        if (prevSongId.equals(nextSongId) && prev.nowPlayingState() == nowPlayingState && prev.position() == pos) {
            return prev;
        }
        return new MiniState(npSong, nowPlayingState, playContext, pos);
    }

    private NowPlayingState getNowPlayingState(PlaybinPlayer.PlayerStates state) {
        return switch (state) {
            case INIT -> NowPlayingState.NONE;
            case BUFFERING -> NowPlayingState.LOADING;
            case READY -> NowPlayingState.PAUSED;
            case PAUSED -> NowPlayingState.PAUSED;
            case PLAYING -> NowPlayingState.PLAYING;
            case END_OF_STREAM -> NowPlayingState.NONE;
        };
    }

    // ---- Playlist menu ----

    private Popover buildMenuPopover() {
        var popoverBox = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(0)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var renameItem = menuItem("Rename\u2026");
        var deleteItem = menuItem("Delete Playlist\u2026");
        deleteItem.addCssClass("destructive-action");

        popoverBox.append(renameItem);
        popoverBox.append(deleteItem);

        var popover = Popover.builder().setChild(popoverBox).build();

        renameItem.onClicked(() -> {
            popover.popdown();
            showRenameDialog();
        });
        deleteItem.onClicked(() -> {
            popover.popdown();
            showDeleteDialog();
        });

        return popover;
    }

    private void showRenameDialog() {
        var playlist = currentPlaylist.get();
        if (playlist == null) {
            return;
        }

        var entry = Entry.builder()
                .setPlaceholderText("Playlist name")
                .setText(playlist.name())
                .setActivatesDefault(true)
                .build();

        var dialog = AlertDialog.builder()
                .setTitle("Rename Playlist")
                .setBody("Enter a new name for \"%s\"".formatted(playlist.name()))
                .build();
        dialog.addResponse("cancel", "_Cancel");
        dialog.addResponse("rename", "_Rename");
        dialog.setResponseAppearance("rename", SUGGESTED);
        dialog.setDefaultResponse("rename");
        dialog.setCloseResponse("cancel");
        dialog.setResponseEnabled("rename", false);
        dialog.setExtraChild(entry);

        var sig = entry.onChanged(() -> {
            var text = entry.getText();
            text = text == null ? "" : text.strip().trim();
            dialog.setResponseEnabled("rename", !text.isBlank() && !text.equals(playlist.name()));
        });

        dialog.onResponse(
                "", response -> {
                    try {
                        if ("rename".equals(response)) {
                            var name = entry.getText().strip().trim();
                            if (!name.isEmpty()) {
                                onAction.apply(new PlayerAction.RenamePlaylist(playlist.id(), name));
                                Utils.runOnMainThread(() -> this.titleLabel.setLabel(name));
                            }
                        }
                    } finally {
                        sig.disconnect();
                    }
                }
        );
        dialog.present(this);
    }

    private void showDeleteDialog() {
        var playlist = currentPlaylist.get();
        if (playlist == null) {
            return;
        }

        AdwDialogHelper.ofDialog(
                this,
                "Delete Playlist",
                "Delete \"%s\"? This cannot be undone.".formatted(playlist.name()),
                List.of(
                        new AdwDialogHelper.Response(CANCEL_LABEL_ID, "_Cancel", DEFAULT),
                        new AdwDialogHelper.Response("delete", "_Delete", DESTRUCTIVE)
                )
        ).thenAccept(result -> {
            if (!"delete".equals(result.label())) {
                return;
            }
            onAction.apply(new PlayerAction.DeletePlaylist(playlist.id()));
        });
    }

    private static Button menuItem(String label) {
        var button = Button.builder().setLabel(label).build();
        button.addCssClass("flat");
        if (button.getChild() instanceof Label child) {
            child.setHalign(START);
            child.addCssClass("body");
        }
        return button;
    }

    // ---- Inner Cell Classes ----

    private static class NowPlayingCell extends Box {
        private final Label trackNumberLabel;
        private final ListItemPlayingIcon playingIcon;
        private ListItem listItem;
        private GSongInfo gSong;
        private volatile NowPlayingState playingState = NowPlayingState.NONE;
        private SignalConnection<NotifyCallback> positionSignal;
        private SignalConnection<NotifyCallback> playingSignal;
        private boolean isCurrentlyPlaying;
        private int listPosition;

        NowPlayingCell() {
            super(HORIZONTAL, 0);
            this.setHalign(CENTER);
            this.setValign(CENTER);
            this.setHexpand(false);

            this.trackNumberLabel = infoLabel("", Classes.labelDim.add(Classes.labelNumeric));
            this.trackNumberLabel.setValign(CENTER);
            this.trackNumberLabel.setHalign(END);
            this.trackNumberLabel.setSingleLineMode(true);
            this.trackNumberLabel.setWidthChars(4);
            this.trackNumberLabel.setMaxWidthChars(4);

            this.playingIcon = new ListItemPlayingIcon(NowPlayingState.NONE, 32);
            this.playingIcon.setVisible(false);
            this.playingIcon.setHalign(CENTER);
            this.playingIcon.setValign(CENTER);

            var overlay = Overlay.builder().setChild(this.trackNumberLabel).build();
            overlay.addOverlay(this.playingIcon);
            this.append(overlay);
        }

        void bind(GSongInfo gSong, ListItem listItem, MiniState state) {
            this.listItem = listItem;
            this.listPosition = listItem.getPosition();
            this.gSong = gSong;
            this.trackNumberLabel.setLabel("%d".formatted(listItem.getPosition() + 1));
            this.playingSignal = this.gSong.onIsPlayingChanged((v) -> {

            });
            this.positionSignal = listItem.onNotify(
                    "position", _ -> {
                        this.listPosition = listItem.getPosition();
                        Utils.runOnMainThread(() -> this.trackNumberLabel.setLabel("%d".formatted(this.listPosition + 1)));
                    }
            );
            this.update(state);
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            var sig = positionSignal;
            if (sig != null) {
                sig.disconnect();
            }
            var sig2 = this.playingSignal;
            if (sig2 != null) {
                sig2.disconnect();
            }
        }

        public void update(MiniState n) {
            if (this.gSong == null) {
                return;
            }
            var next = n.songInfo()
                    .filter(s -> s.id().equals(this.gSong.getSongInfo().id()))
                    .map(_ -> n.nowPlayingState())
                    .orElse(NowPlayingState.NONE);
//            if (next == this.playingState) {
//                return;
//            }
            this.playingState = next;
            Utils.runOnMainThread(() -> {
                var currentSong = this.gSong;
                if (currentSong == null) {
                    return;
                }
                var listItem = this.listItem;
                if (listItem == null) {
                    return;
                }
                var thisPosition = listItem.getPosition();
                // TODO: currentlyPlayingPosition is not sufficient, we also need to know which 'context'
                // currentlyPlayingPosition is only the position in the current playQueue,
                // so could be a position in a shuffled list, or a shuffled list with user-queued additional tracks in it
                int currentlyPlayingPosition = n.position().orElse(-1);
                var isCurrentlyPlayingThisPosition = thisPosition == currentlyPlayingPosition;

                boolean isPlaying = currentSong.getIsPlaying() && isCurrentlyPlayingThisPosition;
                updateCellIsPlaying(isPlaying, this.playingState);
            });
        }

        public void updateCellIsPlaying(boolean isPlayingNow, NowPlayingState playingState) {
            this.playingState = playingState;
            this.isCurrentlyPlaying = isPlayingNow;
            Utils.runOnMainThread(() -> {
                var isPlaying = this.isCurrentlyPlaying;
                switch (this.playingState) {
                    case LOADING, PAUSED, PLAYING -> {
                        this.trackNumberLabel.setVisible(!isPlaying);
                        this.playingIcon.setVisible(isPlaying);
                        this.playingIcon.setPlayingState(isPlaying ? this.playingState : NowPlayingState.NONE);
                    }
                    case NONE -> {
                        this.trackNumberLabel.setVisible(true);
                        this.playingIcon.setVisible(false);
                        this.playingIcon.setPlayingState(isPlaying ? this.playingState : NowPlayingState.NONE);
                    }
                }
            });
        }
    }

    private static class TitleArtistCell extends Box {
        private final Label titleLabel;
        private final Consumer<AppRoute> onNavigate;
        private final ClickLabel artistLabel;
        private GSongInfo gSong;
        private volatile NowPlayingState playingState = NowPlayingState.NONE;
        private ListItem listItem;
        private int listPosition;
        private SignalConnection<NotifyCallback> positionSignal;
        private boolean itemAndPositionIsPlaying;

        TitleArtistCell(Consumer<AppRoute> onNavigate) {
            super(VERTICAL, 2);
            this.onNavigate = onNavigate;
            this.setHalign(START);
            this.setValign(CENTER);
            this.setHexpand(true);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            this.titleLabel = infoLabel("", Classes.title3.add());
            this.titleLabel.setHalign(START);
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setMaxWidthChars(36);
            this.titleLabel.setEllipsize(org.gnome.pango.EllipsizeMode.END);

            this.artistLabel = new ClickLabel(
                    "", () -> {
                if (this.gSong == null) {
                    return;
                }
                var artistId = this.gSong.getSongInfo().artistId();
                if (artistId == null) {
                    return;
                }
                this.onNavigate.accept(new AppNavigation.AppRoute.RouteArtistInfo(artistId));
            }
            );
            this.artistLabel.addCssClass(Classes.labelDim.className());
            this.artistLabel.addCssClass(Classes.caption.className());
            this.artistLabel.setHalign(START);
            this.artistLabel.setSingleLineMode(true);
            this.artistLabel.setEllipsize(org.gnome.pango.EllipsizeMode.END);

            this.append(titleLabel);
            this.append(artistLabel);
        }

        void bind(GSongInfo gSong, ListItem listItem) {
            this.gSong = gSong;
            this.listItem = listItem;
            this.listPosition = listItem.getPosition();
            this.positionSignal = listItem.onNotify("position", (a) -> {
                this.listPosition = listItem.getPosition();
            });
            var info = gSong.getSongInfo();
            this.titleLabel.setLabel(info.title());
            this.artistLabel.setLabel(info.artist() != null ? info.artist() : "");
            // Re-apply playing state for this new song
            Utils.runOnMainThread(() -> {
                switch (this.playingState) {
                    case LOADING, PAUSED, PLAYING -> this.titleLabel.addCssClass(Classes.colorAccent.className());
                    case NONE -> this.titleLabel.removeCssClass(Classes.colorAccent.className());
                }
            });
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            this.listPosition = -1;
            this.positionSignal.disconnect();
            this.titleLabel.removeCssClass(Classes.colorAccent.className());
        }

        public void updateRow(boolean positionIsPlaying) {
            if (this.gSong == null) {
                return;
            }
            this.itemAndPositionIsPlaying = positionIsPlaying;
            Utils.runOnMainThread(() -> {
                if (this.itemAndPositionIsPlaying) {
                    this.titleLabel.addCssClass(Classes.colorAccent.className());
                } else {
                    this.titleLabel.removeCssClass(Classes.colorAccent.className());
                }
            });
        }
    }

    private static class AlbumCell extends Box {
        private final ClickLabel albumLabel;
        private final Consumer<AppRoute> onNavigate;
        private GSongInfo gSong;

        AlbumCell(Consumer<AppRoute> onNavigate) {
            super(HORIZONTAL, 0);
            this.onNavigate = onNavigate;
            this.setHalign(START);
            this.setValign(CENTER);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            this.albumLabel = new ClickLabel(
                    "", () -> {
                if (this.gSong == null) {
                    return;
                }
                var albumId = this.gSong.getSongInfo().albumId();
                if (albumId == null) {
                    return;
                }
                this.onNavigate.accept(new AppNavigation.AppRoute.RouteAlbumInfo(albumId));
            }
            );
            this.albumLabel.addCssClass(Classes.labelDim.className());
            this.albumLabel.addCssClass(Classes.caption.className());
            this.albumLabel.setSingleLineMode(true);
            this.albumLabel.setEllipsize(org.gnome.pango.EllipsizeMode.END);
            this.append(albumLabel);
        }

        void bind(GSongInfo gSong) {
            this.gSong = gSong;
            var album = gSong.getSongInfo().album();
            this.albumLabel.setLabel(album != null ? album : "");
        }
    }

    private static class StarCell extends Box {
        private final StarButton starButton;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        private GSongInfo gSong;
        private final AtomicReference<SignalConnection<?>> favoriteSignal = new AtomicReference<>();

        StarCell(Function<PlayerAction, CompletableFuture<Void>> onAction) {
            super(HORIZONTAL, 0);
            this.onAction = onAction;
            this.setHalign(CENTER);
            this.setValign(CENTER);

            this.starButton = new StarButton(
                    Optional.empty(),
                    newValue -> {
                        if (this.gSong == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        var action = newValue
                                     ? new PlayerAction.Star(this.gSong.getSongInfo())
                                     : new PlayerAction.Unstar(this.gSong.getSongInfo());
                        return this.onAction.apply(action);
                    }
            );
            this.append(starButton);
        }

        void bind(GSongInfo gSong) {
            this.gSong = gSong;
            this.starButton.setStarredAt(gSong.getSongInfo().starred());
            var conn = gSong.onNotify(
                    GSongInfo.Signal.IS_FAVORITE.getId(), _ -> {
                        this.starButton.setStarredAt(this.gSong.getSongInfo().starred());
                    }
            );
            var old = favoriteSignal.getAndSet(conn);
            if (old != null) {
                old.disconnect();
            }
        }

        void unbind() {
            var sig = favoriteSignal.getAndSet(null);
            if (sig != null) {
                sig.disconnect();
            }
        }
    }

    private static class AlbumArtCell extends Box {
        private static final int ART_SIZE = 48;
        private final RoundedAlbumArt albumArt;

        AlbumArtCell(AppManager appManager) {
            super(HORIZONTAL, 0);
            this.setHalign(CENTER);
            this.setValign(CENTER);
            this.albumArt = new RoundedAlbumArt(Optional.empty(), appManager, ART_SIZE);
            this.append(albumArt);
        }

        void bind(GSongInfo gSong) {
            this.albumArt.update(gSong.getSongInfo().coverArt());
        }
    }
}