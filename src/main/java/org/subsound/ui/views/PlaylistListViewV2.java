package org.subsound.ui.views;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.Clamp;
import org.gnome.gdk.ModifierType;
import org.gnome.gio.ListModel;
import org.gnome.gio.ListStore;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.ColumnViewColumn;
import org.gnome.gtk.ColumnViewSorter;
import org.gnome.gtk.CustomFilter;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.FilterChange;
import org.gnome.gtk.FilterListModel;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListScrollFlags;
import org.gnome.gtk.ListTabBehavior;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchBar;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.Separator;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.SortListModel;
import org.gnome.gtk.SortType;
import org.gnome.gtk.Stack;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.javagi.gobject.types.Types;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ObjectIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.ui.components.AdwDialogHelper;
import org.subsound.ui.components.AppNavigation.AppRoute;
import org.subsound.ui.components.ArtistsLinkLabelV3;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.ClickLabel;
import org.subsound.ui.components.Icons;
import org.subsound.ui.components.ListItemPlayingIcon;
import org.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import org.subsound.ui.components.RoundedAlbumArtV2;
import org.subsound.ui.components.SongDownloadStatusIcon;
import org.subsound.ui.components.StarButtonSimple;
import org.subsound.ui.models.GSongInfo;
import org.subsound.utils.Utils;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.subsound.i18n.I18n.tr;
import static com.twelvemonkeys.lang.StringUtil.containsIgnoreCase;
import static org.gnome.adw.ResponseAppearance.DEFAULT;
import static org.gnome.adw.ResponseAppearance.DESTRUCTIVE;
import static org.gnome.adw.ResponseAppearance.SUGGESTED;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.ui.components.AdwDialogHelper.CANCEL_LABEL_ID;
import static org.subsound.ui.views.AlbumInfoPage.infoLabel;

public class PlaylistListViewV2 extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(PlaylistListViewV2.class);

    private final AppManager appManager;
    private final ColumnView columnView;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ScrolledWindow scroll;
    private final Label titleLabel;
    private final Button reloadButton;
    private final MenuButton menuButton;
    private final AtomicReference<MiniState> prevState;
    private final Consumer<AppRoute> onNavigate;
    private final ListStore<GPlaylistEntry> listModel = new ListStore<>();
    private final FilterListModel<GPlaylistEntry> filterModel;
    private final CustomFilter searchFilter;
    private final ColumnViewColumn titleCol;
    private final TitleArtistColumnSorter titleSorter;
    private volatile TitleArtistColumnSorter.States targetSort;
    private volatile String searchQuery = "";
    private final SortListModel<GPlaylistEntry> sortModel;
    private final SingleSelection<GPlaylistEntry> selectionModel;
    private final SearchBar searchBar;
    private final SearchEntry searchEntry;
    // Bound cells indexed by stable server songId. Each entry's qid lives on the cell's
    // boundEntry, so disambiguation between duplicate occurrences is a linear scan over
    // the per-songId list. Typical lists are size 1 (constant time); the pathological case
    // is a playlist of N copies of the same song, which becomes O(N).
    // All access happens on the GTK main thread.
    private final HashMap<String, List<NowPlayingCell>> nowPlayingBySongId = new HashMap<>();
    private final HashMap<String, List<TitleArtistCell>> titleBySongId = new HashMap<>();
    // Direct refs to the two currently-highlighted cells, so clearing on the next state
    // change is O(1) regardless of map state (qid drift, unbind leaks, etc.).
    @Nullable private NowPlayingCell highlightedNowPlayingCell = null;
    @Nullable private TitleArtistCell highlightedTitleCell = null;
    private final AtomicReference<ServerClient.PlaylistSimple> currentPlaylist = new AtomicReference<>();
    private volatile boolean reloadNeeded = false;
    private volatile int lastKnownSongCount = 0;
    @Nullable
    private SignalConnection<?> playlistNotifySignal = null;
    private final AtomicLong setSongsGeneration = new AtomicLong();
    @Nullable
    private SignalConnection<?> sourceItemsChangedSignal = null;
    @Nullable
    private ListModel<GSongInfo> boundSource = null;

    /**
     * Wrapper GObject that pairs a GSongInfo with its original playlist position,
     * enabling a "revert to playlist order" sort without a side-table.
     */
    public static class GPlaylistEntry extends GObject {
        public static final Type gtype = Types.register(GPlaylistEntry.class);

        private GSongInfo gSong;
        private int position;
        private String queueItemId;

        public GPlaylistEntry(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        public static String makeQueueItemId(ObjectIdentifier identifier, String songId, int position) {
            return "%s||%s||%s".formatted(identifier.getId(), songId, position);
        }
        public static String makeQueueItemId(String playlistId, String songId, int position) {
            return "%s||%s||%s".formatted(playlistId, songId, position);
        }
        public static GPlaylistEntry of(String playlistId, GSongInfo gSong, int position) {
            var instance = (GPlaylistEntry) GObject.newInstance(getType());
            instance.gSong = gSong;
            instance.position = position;
            instance.queueItemId = makeQueueItemId(playlistId, gSong.getId(), position);
            return instance;
        }

        public @Nullable String getQueueItemId() {
            return queueItemId;
        }

        public PlayItemId playItemId() {
            return new PlayItemId(gSong.getSongInfo().id(), queueItemId);
        }

        public GSongInfo gSong() {
            return gSong;
        }

        public SongInfo song() {
            return gSong.getSongInfo();
        }

        public int position() {
            return position;
        }

        public void setPosition(int p) {
            this.position = p;
        }

        public void setQueueItemId(String qid) {
            this.queueItemId = qid;
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
        this.columnView = ColumnView.builder()
                .setShowRowSeparators(false)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .setTabBehavior(ListTabBehavior.ITEM)
                .build();

        // 2. Insert a filter (for the Ctrl+F search bar) between the raw list and the sorter.
        // The filter reads `searchQuery` and matches title/artist/album case-insensitively.
        this.searchFilter = new CustomFilter(item -> {
            var q = this.searchQuery;
            if (q == null || q.isEmpty()) {
                return true;
            }
            if (!(item instanceof GPlaylistEntry entry)) {
                return true;
            }
            var song = entry.song();
            return containsIgnoreCase(song.title(), q)
                   || containsIgnoreCase(song.artistName(), q)
                   || containsIgnoreCase(song.album(), q);
        });
        this.filterModel = new FilterListModel<>(this.listModel, this.searchFilter);

        // 3. Wire sorting over the filtered model: ColumnView aggregates per-column sorters.
        this.sortModel = new SortListModel<>(this.filterModel, this.columnView.getSorter());

        // Search bar — revealer that slides down above the list. Must be constructed
        // before the key controller since the Ctrl+F handler references these fields.
        this.searchEntry = SearchEntry.builder()
                .setPlaceholderText(tr("Filter by title, artist, or album"))
                .setHexpand(true)
                .build();
        this.searchBar = SearchBar.builder()
                .setChild(this.searchEntry)
                .setShowCloseButton(true)
                .build();
        this.searchBar.addCssClass("filter-searchbar");
        this.searchBar.connectEntry(this.searchEntry);
        // Typing printable chars while focus is anywhere in this view pops the bar open.
        this.searchBar.setKeyCaptureWidget(this);
        this.searchEntry.onSearchChanged(() -> {
            var text = this.searchEntry.getText();
            this.searchQuery = text == null ? "" : text.trim();
            this.searchFilter.changed(FilterChange.DIFFERENT);
        });
        this.searchEntry.onStopSearch(() -> {
            // Escape pressed inside the entry — close and clear the filter.
            this.searchBar.setSearchMode(false);
            this.searchEntry.setText("");
        });

        // 4. Selection wraps sort model
        this.selectionModel = new SingleSelection<>(this.sortModel);
        this.columnView.setModel(this.selectionModel);


        // 5. Build columns with per-column factories and sorters, then append them

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
                var currentState = appManager.getState();
                var playingItem = buildPlayItemId(currentState);
                var nowPlayingState = getNowPlayingState(currentState.player().state());
                boolean isPlaying = cell.bind(entry, listItem, nowPlayingState, playingItem);
                nowPlayingBySongId.computeIfAbsent(entry.song().id(), _ -> new ArrayList<>()).add(cell);
                // A cell highlighted at bind time must be registered for O(1) clearing on the
                // next song change, or the highlight leaks when refreshAllHighlights ran while
                // this row was offscreen (typical after playNext in shuffle mode).
                if (isPlaying) {
                    if (this.highlightedNowPlayingCell != null && this.highlightedNowPlayingCell != cell) {
                        this.highlightedNowPlayingCell.updateCellIsPlaying(false, NowPlayingState.NONE);
                    }
                    this.highlightedNowPlayingCell = cell;
                }
            }
        });
        nowPlayingFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
                if (entry != null) {
                    removeFromSongList(nowPlayingBySongId, entry.song().id(), cell);
                }
                cell.unbind();
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
        this.columnView.appendColumn(nowPlayingCol);

        // --- Title column (expand, sortable by title) ---
        var titleFactory = new SignalListItemFactory();
        titleFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new TitleArtistCell(appManager, this.onNavigate);
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
                var playingItem = buildPlayItemId(appManager.getState());
                boolean isPlaying = cell.bind(entry, listItem, playingItem);
                titleBySongId.computeIfAbsent(entry.song().id(), _ -> new ArrayList<>()).add(cell);
                // See nowPlayingFactory.onBind: register bind-time highlights for later clearing.
                if (isPlaying) {
                    if (this.highlightedTitleCell != null && this.highlightedTitleCell != cell) {
                        this.highlightedTitleCell.updateRow(false);
                    }
                    this.highlightedTitleCell = cell;
                }
            }
        });
        titleFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            var child = listItem.getChild();
            if (child instanceof TitleArtistCell cell) {
                if (entry != null) {
                    removeFromSongList(titleBySongId, entry.song().id(), cell);
                }
                cell.unbind();
            }
        });
        titleFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        this.titleSorter = new TitleArtistColumnSorter();
        final String titleColDefaultTitle = tr("Title");
        this.titleCol = new ColumnViewColumn(titleColDefaultTitle, titleFactory);
        titleCol.setExpand(true);
        titleCol.setSorter(titleSorter);
        this.columnView.appendColumn(titleCol);

        // ColumnViewSorter is the global columnview sorter that controls the columns:
        ColumnViewSorter cvs = (ColumnViewSorter) this.columnView.getSorter();
        cvs.onChanged(change -> {
            var ps = cvs.getPrimarySortColumn();
            boolean isTitle = titleCol == ps;
            if (isTitle) {
                if (this.targetSort != null) {
                    var copy = this.targetSort;
                    this.targetSort = null;
                    Utils.runOnMainThread(() -> {
                        var title = switch (copy) {
                            case NONE -> titleColDefaultTitle;
                            case ARTIST_ASC -> "Artist";
                            case ARTIST_DESC -> "Artist";
                            case TITLE_ASC -> "Title";
                            case TITLE_DESC -> "Title";
                        };
                        titleCol.setTitle(title);
                        if (copy == TitleArtistColumnSorter.States.NONE) {
                            // reset back to default sorting
                            this.resetColumnSorting();
                        }
                        //System.out.println("columnView: sorter.onChanged: %s isTitle=%b this.targetSort=%s".formatted(change.name(), isTitle, this.targetSort));
                    });
                    return;
                } else {
                    var next = titleSorter.next();
                    this.targetSort = next;
                    //System.out.println("columnView: sorter.onChanged: %s isTitle=%b next=%s".formatted(change.name(), isTitle, next.name()));
                }
            } else {
                // fallthrough means using the included column sorter
                //System.out.println("columnView: sorter.onChanged: %s isTitle=%b".formatted(change.name(), isTitle));
            }
        });

        // --- Album column (200px, sortable by album) ---
        var albumFactory = new SignalListItemFactory();
        albumFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new AlbumCell(this.onNavigate);
            listItem.setChild(cell);
        });
        albumFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof AlbumCell cell) {
                cell.bind(listItem, entry);
            }
        });
        albumFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            if (listItem.getChild() instanceof AlbumCell cell) {
                cell.unbind();
            }
        });
        albumFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var albumSorter = new PlaylistEntrySorter((a, b) -> {
            var albumA = a.song().album();
            var albumB = b.song().album();
            albumA = albumA == null ? "" : albumA;
            albumB = albumB == null ? "" : albumB;
            return albumA.compareToIgnoreCase(albumB);
        });
        var albumCol = new ColumnViewColumn(tr("Album"), albumFactory);
        albumCol.setFixedWidth(200);
        albumCol.setSorter(albumSorter);
        this.columnView.appendColumn(albumCol);

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
                label.setLabel(Utils.formatDurationShort(entry.song().duration()));
            }
        });
        durationFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var durationSorter = new PlaylistEntrySorter(Comparator.comparingLong(a -> a.song().duration().toMillis()));
        var durationCol = new ColumnViewColumn(tr("Duration"), durationFactory);
        durationCol.setFixedWidth(80);
        durationCol.setSorter(durationSorter);
        this.columnView.appendColumn(durationCol);

        // --- Actions column (star + menu button, sortable by starred presence) ---
        var starFactory = new SignalListItemFactory();
        starFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new ActionCell(this.onAction));
        });
        starFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof ActionCell cell) {
                cell.bind(listItem, entry);
            }
        });
        starFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof ActionCell cell) {
                cell.unbind();
            }
        });
        starFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var starSorter = new PlaylistEntrySorter((a, b) -> {
            var sa = a.song().starred().isPresent();
            var sb = b.song().starred().isPresent();
            return Boolean.compare(sb, sa);
        });
        var starCol = new ColumnViewColumn("★", starFactory);
        starCol.setFixedWidth(120);
        starCol.setSorter(starSorter);
        this.columnView.appendColumn(starCol);

        // Activate: play in sorted order so next/prev respect current sort.
        // Assign a UUID to every entry in the current sorted view so highlighting
        // survives re-sort and shuffle.
        var activateSignal = this.columnView.onActivate(index -> {
            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return;
            }
            var entry = this.sortModel.getItem(index);
            if (entry == null) {
                return;
            }
            log.info("listView.onActivate: {} {}", index, entry.song().title());
            int n = (int) this.sortModel.getNItems();
            var slots = new ArrayList<PlayerAction.QueueSlot>(n);
            for (int i = 0; i < n; i++) {
                var e = this.sortModel.getItem(i);
                slots.add(new PlayerAction.QueueSlot(e.getQueueItemId(), e.song()));
            }
            var playContext = new PlaylistIdentifier(playlist.id());
            this.onAction.apply(new PlayerAction.PlayAndReplaceQueue(playContext, slots, index));
        });

        var keyController = new EventControllerKey();
        keyController.setPropagationPhase(PropagationPhase.CAPTURE);
        keyController.onKeyPressed((keyval, keycode, state) -> {
            // GDK_KEY_f = 0x0066 — Ctrl+F toggles the search bar
            if (keyval == 0x66 && state.contains(ModifierType.CONTROL_MASK)) {
                this.searchBar.setSearchMode(!this.searchBar.getSearchMode());
                if (this.searchBar.getSearchMode()) {
                    this.searchEntry.grabFocus();
                }
                return true;
            }
            // GDK_KEY_space = 0x0020
            if (keyval == 0x20) {
                if (appManager.getState().player().state().isPlaying()) {
                    this.onAction.apply(new PlayerAction.Pause());
                } else {
                    this.onAction.apply(new PlayerAction.Play());
                }
                return true;
            }
            if (keyval != 0xFFFF) {
                return false; // GDK_KEY_Delete
            }
            var playlist = currentPlaylist.get();
            if (playlist == null) {
                return false;
            }
            var entry = selectionModel.getSelectedItem();
            if (entry == null) {
                return false;
            }
            if (playlist.kind() == PlaylistKind.NORMAL) {
                removeFromPlaylist(entry);
                return true;
            }
            if (playlist.kind() == PlaylistKind.DOWNLOADED) {
                removeFromDownloads(entry);
                return true;
            }
            return false;
        });
        this.columnView.addController(keyController);

        var mapSignal = this.onMap(() -> {
            appManager.addOnStateChanged(this);

            // Reconcile against current state — Gtk.Stack unmaps non-visible children, so
            // onStateChanged emissions during a LOADING crossfade are dropped. Walking all
            // bound cells here clears any stale highlight and applies the right one.
            var currentAppState = appManager.getState();
            var current = selectState(this.prevState.get(), currentAppState);
            this.prevState.set(current);
            var nextPlaying = current.playingItem();
            var playingState = current.nowPlayingState();
            Utils.runOnMainThread(() -> refreshAllHighlights(nextPlaying, playingState));

            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return;
            }

            if (reloadNeeded && playlist.kind() == PlaylistKind.NORMAL) {
                reloadNeeded = false;
                this.refreshCurrentPlaylistAsync();
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
            unbindSource();
            mapSignal.disconnect();
            unmapSignal.disconnect();
            activateSignal.disconnect();
        });

        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .build();
        this.scroll.setChild(this.columnView);

        this.titleLabel = new Label();
        this.titleLabel.setLabel("");
        this.titleLabel.setHalign(START);
        this.titleLabel.setHexpand(true);
        this.titleLabel.setMaxWidthChars(50);
        this.titleLabel.setEllipsize(EllipsizeMode.END);
        this.titleLabel.setSingleLineMode(true);
        this.titleLabel.addCssClass("title-2");

        this.menuButton = MenuButton.builder()
                .setIconName(Icons.OpenMenu.getIconName())
                .setPopover(buildMenuPopover())
                .setValign(CENTER)
                .setVisible(false)
                .build();
        this.menuButton.addCssClass("flat");
        this.menuButton.addCssClass("circular");
        this.reloadButton = new Button();
        this.reloadButton.setTooltipText(tr("Refresh playlist"));
        //this.reloadButton.setLabel("Sync library");
        this.reloadButton.setIconName("view-refresh-symbolic");
        this.reloadButton.addCssClass("flat");
        this.reloadButton.addCssClass("circular");
        this.reloadButton.onClicked(() -> {
            //this.appManager.handleAction(new PlayerAction.RefreshPlaylist());
            this.reloadNeeded = false;
            this.refreshCurrentPlaylistAsync();
        });

        var headerBox = new Box(HORIZONTAL, 0);
        headerBox.setMarginTop(12);
        headerBox.setMarginBottom(8);
        headerBox.setMarginStart(16);
        headerBox.setMarginEnd(8);
        headerBox.append(this.titleLabel);
        headerBox.append(this.reloadButton);
        headerBox.append(this.menuButton);

        this.append(headerBox);
        this.append(this.searchBar);
        this.append(this.scroll);
    }

    private void resetColumnSorting() {
        // this should be safe since we also reset columnView.sortByColumn right after:
        this.titleSorter.resetSorter();
        this.titleCol.setTitle(tr("Title"));
        this.columnView.sortByColumn(null, SortType.ASCENDING);
    }

    private CompletableFuture<ServerClient.Playlist> refreshCurrentPlaylistAsync() {
        var songStore = appManager.getSongStore();
        return Utils.doAsync(() -> {
            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return null;
            }
            // Synthetic playlists (Downloaded, Starred) have no server-side representation —
            // hitting getPlaylist(id) would fail. They are refreshed via their own sources
            // (DownloadManager / StarredListStore) when underlying data changes.
            if (playlist.kind() != PlaylistKind.NORMAL) {
                return null;
            }
            var newPlaylist = appManager
                    .useClient(cl -> cl.getPlaylist(playlist.id()));

            // playlistid changed while loading:
            if (!this.currentPlaylist.get().id().equals(newPlaylist.id())) {
                return newPlaylist;
            }
            var songs = newPlaylist.songs().stream()
                    .map(songStore::newInstance)
                    .toList();
            setSongs(songs, playlist);
            return newPlaylist;
        });
    }

    private static final int SPLICE_CHUNK_SIZE = 50;
    // GTK4 ColumnView only realizes the first ~230 rows; per-item attach cost is paid for
    // those but is essentially free past that point. Chunk the visible prefix to keep the
    // main loop responsive (fade-in animation), then splice the tail in one call.
    private static final int CHUNKED_PREFIX = 250;

    /**
     * Replace the displayed list with {@code songs}. The returned future completes on the
     * GTK main thread when the last chunk has landed (i.e. the ColumnView has finished
     * reacting to all items-changed events). Callers that need to drop a loading
     * affordance should chain off this future.
     *
     * <p>The splice is chunked: ColumnView's per-item reaction (≈ 1–4ms/item via FFI) is
     * unavoidable, but splitting the work into ~50-row batches and yielding the main loop
     * between them keeps animations running and the UI responsive instead of frozen for
     * the full duration. If a newer {@code setSongs} starts before chunks finish, the
     * earlier call's remaining chunks are aborted via a generation token.
     */
    public CompletableFuture<Void> setSongs(List<GSongInfo> songs, ServerClient.PlaylistSimple playlist) {
        long tStart = System.nanoTime();
        long generation = this.setSongsGeneration.incrementAndGet();
        var prev = this.currentPlaylist.get();
        boolean playlistChanged = prev == null || !prev.id().equals(playlist.id());
        this.currentPlaylist.set(playlist);
        this.lastKnownSongCount = playlist.songCount();
        this.reloadNeeded = false;
        var playlistId = playlist.id();
        int n = songs.size();
        var items = new GPlaylistEntry[n];
        for (int i = 0; i < n; i++) {
            var song = songs.get(i);
            items[i] = GPlaylistEntry.of(playlistId, song, i);
        }
        long tAfterAlloc = System.nanoTime();
        var done = new CompletableFuture<Void>();
        Utils.runOnMainThread(() -> {
            if (this.setSongsGeneration.get() != generation) {
                done.complete(null);
                return;
            }
            long tMainStart = System.nanoTime();
            this.titleLabel.setLabel(playlist.name());
            this.menuButton.setVisible(playlist.kind() == PlaylistKind.NORMAL);
            this.reloadButton.setVisible(playlist.kind() == PlaylistKind.NORMAL);
            if (playlistChanged) {
                // Reset the search/filter when navigating to a different playlist.
                this.searchEntry.setText("");
                this.searchBar.setSearchMode(false);
                this.searchQuery = "";
                this.searchFilter.changed(FilterChange.DIFFERENT);
            }
            int oldN = this.listModel.getNItems();
            if (oldN > 0 && n > 0) {
                this.columnView.scrollTo(0, this.titleCol, ListScrollFlags.NONE, null);
            }
            this.listModel.splice(0, oldN, new GPlaylistEntry[0]);
            this.resetColumnSorting();
            long tAfterClear = System.nanoTime();

            // Subscribe to GPlaylist metadata changes for the current playlist
            if (this.playlistNotifySignal != null) {
                this.playlistNotifySignal.disconnect();
                this.playlistNotifySignal = null;
            }
            if (playlist.kind() == PlaylistKind.NORMAL) {
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

            spliceChunked(items, 0, generation, done, tStart, tAfterAlloc, tMainStart, oldN, tAfterClear);
        });
        return done;
    }

    private void spliceChunked(GPlaylistEntry[] items, int from, long generation, CompletableFuture<Void> done,
                                long tStart, long tAfterAlloc, long tMainStart, int oldN, long tAfterClear) {
        if (this.setSongsGeneration.get() != generation) {
            done.complete(null);
            return;
        }
        int prefixEnd = Math.min(CHUNKED_PREFIX, items.length);
        int end;
        if (from < prefixEnd) {
            // Within the visible-prefix window: small chunk so the main loop can render
            // the fade-in animation between batches.
            end = Math.min(from + SPLICE_CHUNK_SIZE, prefixEnd);
        } else {
            // Past the prefix: ColumnView won't realize these rows, so the per-item attach
            // cost is negligible. Dump the rest in one splice and finish.
            end = items.length;
        }
        if (end > from) {
            var chunk = new GPlaylistEntry[end - from];
            System.arraycopy(items, from, chunk, 0, end - from);
            this.listModel.splice(this.listModel.getNItems(), 0, chunk);
        }
        if (end < items.length) {
            Utils.runOnMainThread(() -> spliceChunked(items, end, generation, done,
                    tStart, tAfterAlloc, tMainStart, oldN, tAfterClear));
        } else {
            long tMainEnd = System.nanoTime();
            log.info(
                    "setSongs: n={} alloc={}ms mainEnter={}ms clear(old={})={}ms chunkedAdd={}ms total(main)={}ms total(all)={}ms",
                    items.length,
                    (tAfterAlloc - tStart) / 1_000_000,
                    (tMainStart - tAfterAlloc) / 1_000_000,
                    oldN,
                    (tAfterClear - tMainStart) / 1_000_000,
                    (tMainEnd - tAfterClear) / 1_000_000,
                    (tMainEnd - tMainStart) / 1_000_000,
                    (tMainEnd - tStart) / 1_000_000
            );
            done.complete(null);
        }
    }

    /**
     * Bind this view to a foreign {@link ListModel} of {@link GSongInfo}, so that adds/removes
     * in the source store are reflected here without rebuilding the list. The initial contents
     * are pushed through {@link #setSongs}; subsequent itemsChanged notifications from the
     * source are translated to splices into this view's internal model.
     *
     * <p>Any prior binding is detached first. Call {@link #unbindSource} when the view is
     * being discarded (also wired into {@code onDestroy}).
     */
    public void bindSource(ListModel<GSongInfo> source, ServerClient.PlaylistSimple playlist) {
        unbindSource();
        this.boundSource = source;

        int n = source.getNItems();
        var initial = new ArrayList<GSongInfo>(n);
        for (int i = 0; i < n; i++) {
            initial.add((GSongInfo) source.getItem(i));
        }
        setSongs(initial, playlist);

        this.sourceItemsChangedSignal = source.onItemsChanged((position, nRemoved, nAdded) -> {
            var current = this.currentPlaylist.get();
            if (current == null) {
                return;
            }
            var playlistId = current.id();
            var added = new GPlaylistEntry[(int) nAdded];
            for (int i = 0; i < nAdded; i++) {
                var song = (GSongInfo) source.getItem((int) position + i);
                if (song == null) {
                    // Source mutated under us; bail out and let a later event reconcile.
                    return;
                }
                added[i] = GPlaylistEntry.of(playlistId, song, (int) position + i);
            }
            Utils.runOnMainThread(() -> {
                this.listModel.splice((int) position, (int) nRemoved, added);
                // Renumber the tail so GPlaylistEntry.position stays consistent with index,
                // and rebuild queueItemId — it embeds position, and applyHighlightFor /
                // clearHighlightFor look entries up by exact qid match.
                int total = this.listModel.getNItems();
                int tailStart = (int) position + (int) nAdded;
                for (int i = tailStart; i < total; i++) {
                    var entry = this.listModel.getItem(i);
                    if (entry != null) {
                        entry.setPosition(i);
                        entry.setQueueItemId(GPlaylistEntry.makeQueueItemId(playlistId, entry.song().id(), i));
                    }
                }
            });
        });
    }

    public void unbindSource() {
        if (this.sourceItemsChangedSignal != null) {
            this.sourceItemsChangedSignal.disconnect();
            this.sourceItemsChangedSignal = null;
        }
        this.boundSource = null;
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
        var playingState = next.nowPlayingState();

        // The playing-item identity (songId + queueItemId) is the source of truth. Triggering
        // off songId/position alone misses the shuffle-activate case where only the queue's
        // queueItemId changes while nowPlaying.song.id and queue.position stay the same.
        boolean playingItemChanged = !prev.playingItem().equals(next.playingItem());
        boolean stateChanged = prev.nowPlayingState() != next.nowPlayingState();
        if (!playingItemChanged && !stateChanged) {
            return;
        }
        var nextPlaying = next.playingItem();
        Utils.runOnMainThread(() -> refreshAllHighlights(nextPlaying, playingState));
    }

    /**
     * Clear whichever cells were last highlighted, then highlight the cells matching
     * {@code playingItem}. Clearing is O(1) via cached refs; finding the new cell is
     * O(songId-bucket size), typically 1.
     *
     * <p>The cached cell may have been recycled to a different entry since we highlighted
     * it. We clear it unconditionally: {@code NowPlayingCell.unbind()} does not reset its
     * visual state, so a recycled-but-not-yet-rebound cell can carry a stale highlight.
     * If the cell happens to be the one that should now be highlighted, the apply pass
     * below restores it.
     *
     * <p>Must be called on the GTK main thread.
     */
    private void refreshAllHighlights(Optional<PlayItemId> playingItem, NowPlayingState playingState) {
        if (this.highlightedNowPlayingCell != null) {
            this.highlightedNowPlayingCell.updateCellIsPlaying(false, NowPlayingState.NONE);
            this.highlightedNowPlayingCell = null;
        }
        if (this.highlightedTitleCell != null) {
            this.highlightedTitleCell.updateRow(false);
            this.highlightedTitleCell = null;
        }

        if (playingItem.isEmpty()) {
            return;
        }
        var p = playingItem.get();

        var nowList = nowPlayingBySongId.get(p.songId());
        if (nowList != null) {
            for (var c : nowList) {
                var be = c.boundEntry;
                if (be != null && p.queueItemId().equals(be.getQueueItemId())) {
                    c.updateCellIsPlaying(true, playingState);
                    this.highlightedNowPlayingCell = c;
                    break;
                }
            }
        }
        var titleList = titleBySongId.get(p.songId());
        if (titleList != null) {
            for (var c : titleList) {
                var be = c.boundEntry;
                if (be != null && p.queueItemId().equals(be.getQueueItemId())) {
                    c.updateRow(true);
                    this.highlightedTitleCell = c;
                    break;
                }
            }
        }
    }

    public record PlayItemId(String songId, String queueItemId) {}

    public record MiniState(
            Optional<SongInfo> songInfo,
            NowPlayingState nowPlayingState,
            Optional<ObjectIdentifier> playContext,
            Optional<Integer> position,
            Optional<PlayItemId> playingItem
    ){}

    private static Optional<PlayItemId> buildPlayItemId(AppManager.AppState state) {
        var songId = state.nowPlaying().map(np -> np.song().id()).orElse("");
        return state.queue().playingItemId().map(qid -> new PlayItemId(songId, qid));
    }

    private static <C> void removeFromSongList(HashMap<String, List<C>> map, String songId, C cell) {
        var list = map.get(songId);
        if (list != null) {
            list.remove(cell);
            if (list.isEmpty()) {
                map.remove(songId);
            }
        }
    }

    private MiniState selectState(@Nullable MiniState prev, AppManager.AppState state) {
        var npSong = state.nowPlaying().map(AppManager.NowPlaying::song);
        var nowPlayingState = getNowPlayingState(state.player().state());
        var playingItem = buildPlayItemId(state);
        if (prev == null) {
            return new MiniState(npSong, nowPlayingState, state.queue().playContext(), state.queue().position(), playingItem);
        }
        var prevSongId = prev.songInfo().map(SongInfo::id).orElse("");
        var nextSongId = npSong.map(SongInfo::id).orElse("");
        var playContext = state.queue().playContext();
        var pos = state.queue().position();
        if (prevSongId.equals(nextSongId)
            && prev.nowPlayingState() == nowPlayingState
            && prev.position().equals(pos)
            && prev.playingItem().equals(playingItem)) {
            return prev;
        }
        return new MiniState(npSong, nowPlayingState, playContext, pos, playingItem);
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

        var renameItem = menuItem(tr("Rename\u2026"));
        var downloadAllItem = menuItem(tr("Download all"));
        var deleteItem = menuItem(tr("Delete Playlist\u2026"));
        deleteItem.addCssClass("destructive-action");

        popoverBox.append(renameItem);
        popoverBox.append(downloadAllItem);
        popoverBox.append(deleteItem);

        var popover = Popover.builder().setChild(popoverBox).build();

        renameItem.onClicked(() -> {
            popover.popdown();
            showRenameDialog();
        });
        downloadAllItem.onClicked(() -> {
            popover.popdown();
            downloadAll();
        });
        deleteItem.onClicked(() -> {
            popover.popdown();
            showDeleteDialog();
        });

        return popover;
    }

    private void downloadAll() {
        int n = listModel.getNItems();
        var songs = new ArrayList<GSongInfo>(n);
        for (int i = 0; i < n; i++) {
            songs.add(listModel.getItem(i).gSong());
        }
        if (!songs.isEmpty()) {
            onAction.apply(new PlayerAction.AddManyToDownloadQueue(songs));
        }
    }

    private void showRenameDialog() {
        var playlist = currentPlaylist.get();
        if (playlist == null) {
            return;
        }

        var entry = Entry.builder()
                .setPlaceholderText(tr("Playlist name"))
                .setText(playlist.name())
                .setActivatesDefault(true)
                .build();

        var dialog = AlertDialog.builder()
                .setTitle(tr("Rename Playlist"))
                .setBody(tr("Enter a new name for \"%s\"").formatted(playlist.name()))
                .build();
        // TRANSLATORS: "_" marks the mnemonic key
        dialog.addResponse("cancel", tr("_Cancel"));
        dialog.addResponse("rename", tr("_Rename"));
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
                tr("Delete Playlist"),
                tr("Delete \"%s\"? This cannot be undone.").formatted(playlist.name()),
                List.of(
                        // TRANSLATORS: "_" marks the mnemonic key
                        new AdwDialogHelper.Response(CANCEL_LABEL_ID, tr("_Cancel"), DEFAULT),
                        new AdwDialogHelper.Response("delete", tr("_Delete"), DESTRUCTIVE)
                )
        ).thenAccept(result -> {
            if (!"delete".equals(result.label())) {
                return;
            }
            onAction.apply(new PlayerAction.DeletePlaylist(playlist.id()));
        });
    }

    private void removeFromPlaylist(GPlaylistEntry entry) {
        var playlist = currentPlaylist.get();
        if (playlist == null || playlist.kind() != PlaylistKind.NORMAL) {
            return;
        }
        int deletedPos = entry.position();
        this.onAction.apply(new PlayerAction.RemoveFromPlaylist(
                entry.song(),
                deletedPos,
                playlist.id(),
                playlist.name()
        ));
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
    }

    private void removeFromDownloads(GPlaylistEntry entry) {
        var playlist = currentPlaylist.get();
        if (playlist == null || playlist.kind() != PlaylistKind.DOWNLOADED) {
            return;
        }
        this.onAction.apply(new PlayerAction.RemoveFromPlaylist(
                entry.song(),
                entry.position(),
                playlist.id(),
                playlist.name()
        ));
        Utils.runOnMainThread(() -> {
            for (int i = 0; i < listModel.getNItems(); i++) {
                if (listModel.getItem(i) == entry) {
                    listModel.remove(i);
                    break;
                }
            }
        });
    }

    private Popover buildRowContextMenu(GPlaylistEntry entry) {
        var stack = new Stack();

        var menuContent = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(2)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var menuPopover = new Popover();
        var menuClamp = new Clamp();
        menuClamp.setMaximumSize(220);
        menuClamp.setChild(stack);
        menuPopover.setChild(menuClamp);

        var playlist = currentPlaylist.get();
        var playlistId = playlist != null ? playlist.id() : null;
        var playlistIdentifier = playlistId != null ? new PlaylistIdentifier(playlistId) : null;

        var playMenuItem = menuItem(tr("Play"));
        playMenuItem.onClicked(() -> {
            menuPopover.popdown();
            if (playlistIdentifier == null) {
                return;
            }
            int n = sortModel.getNItems();
            var slots = new ArrayList<PlayerAction.QueueSlot>(n);
            for (int i = 0; i < n; i++) {
                var e = sortModel.getItem(i);
                slots.add(new PlayerAction.QueueSlot(e.getQueueItemId(), e.song()));
            }
            // find the index of this entry in the sorted model
            int idx = 0;
            for (int i = 0; i < n; i++) {
                if (sortModel.getItem(i) == entry) {
                    idx = i;
                    break;
                }
            }
            onAction.apply(new PlayerAction.PlayAndReplaceQueue(playlistIdentifier, slots, idx));
        });

        var playNextMenuItem = menuItem(tr("Play Next"));
        playNextMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.Enqueue(entry.song()));
        });

        var addToQueueMenuItem = menuItem(tr("Add to Queue"));
        addToQueueMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.EnqueueLast(entry.song()));
        });

        var addToPlaylistMenuItem = menuItem(tr("Add to Playlist\u2026"));
        addToPlaylistMenuItem.onClicked(() -> stack.setVisibleChildName("playlists"));

        var downloadMenuItem = menuItem(tr("Download"));
        downloadMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.AddToDownloadQueue(entry.song()));
        });

        menuContent.append(playMenuItem);
        menuContent.append(playNextMenuItem);
        menuContent.append(addToQueueMenuItem);
        menuContent.append(addToPlaylistMenuItem);
        menuContent.append(downloadMenuItem);

        if (playlist != null && playlist.kind() == PlaylistKind.NORMAL) {
            var removeMenuItem = menuItem(tr("Remove from Playlist"));
            removeMenuItem.onClicked(() -> {
                menuPopover.popdown();
                removeFromPlaylist(entry);
            });
            menuContent.append(removeMenuItem);
        }

        if (playlist != null && playlist.kind() == PlaylistKind.DOWNLOADED) {
            var removeMenuItem = menuItem(tr("Remove from Downloads"));
            removeMenuItem.addCssClass("destructive-action");
            removeMenuItem.onClicked(() -> {
                menuPopover.popdown();
                removeFromDownloads(entry);
            });
            menuContent.append(removeMenuItem);
        }

        // Playlist submenu
        var playlistsView = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(2)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var backButton = menuItem(tr("\u2190 Back"));
        backButton.onClicked(() -> stack.setVisibleChildName("main"));
        playlistsView.append(backButton);
        playlistsView.append(new Separator(HORIZONTAL));

        var playlists = appManager.getPlaylistsListStore();
        for (int i = 0; i < playlists.getNItems(); i++) {
            GPlaylist gPlaylist = playlists.getItem(i);
            if (gPlaylist.getPlaylist().kind() != PlaylistKind.NORMAL) {
                continue;
            }
            String pid = gPlaylist.getId();
            String pname = gPlaylist.getName();
            var btn = menuItem(pname);
            btn.onClicked(() -> {
                menuPopover.popdown();
                onAction.apply(new PlayerAction.AddToPlaylist(entry.song(), pid, pname));
            });
            playlistsView.append(btn);
        }

        var playlistsScroll = ScrolledWindow.builder()
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .setMaxContentHeight(300)
                .build();
        playlistsScroll.setChild(playlistsView);

        stack.addNamed(menuContent, "main");
        stack.addNamed(playlistsScroll, "playlists");
        stack.setVisibleChildName("main");

        menuPopover.onClosed(() -> stack.setVisibleChildName("main"));

        return menuPopover;
    }

    private static Button menuItem(String label) {
        var button = Button.builder().setLabel(label).build();
        button.addCssClass("flat");
        if (button.getChild() instanceof Label child) {
            child.setHalign(START);
            child.addCssClass("body");
            child.setSingleLineMode(true);
            child.setEllipsize(EllipsizeMode.END);
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
        private boolean isCurrentlyPlaying;
        @Nullable volatile GPlaylistEntry boundEntry;

        NowPlayingCell() {
            super(HORIZONTAL, 0);
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);

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
            overlay.setHexpand(true);
            overlay.setVexpand(true);
            overlay.addOverlay(this.playingIcon);
            this.append(overlay);
        }

        boolean bind(GPlaylistEntry entry, ListItem listItem, NowPlayingState nowPlayingState, Optional<PlayItemId> playingItem) {
            this.boundEntry = entry;
            this.gSong = entry.gSong();
            this.listItem = listItem;
            this.trackNumberLabel.setLabel("%d".formatted(listItem.getPosition() + 1));
            // GTK notify:: fires on the main thread; no idle hop needed.
            this.positionSignal = listItem.onNotify(
                    "position", _ -> this.trackNumberLabel.setLabel("%d".formatted(listItem.getPosition() + 1))
            );
            boolean isPlaying = isPlayingEntry(entry.playItemId(), playingItem);
            updateCellIsPlaying(isPlaying, nowPlayingState);
            return isPlaying;
        }

        private static boolean isPlayingEntry(PlayItemId entryItem, Optional<PlayItemId> playingItem) {
            return playingItem.map(p -> p.queueItemId().equals(entryItem.queueItemId())).orElse(false);
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            this.boundEntry = null;
            var sig = positionSignal;
            if (sig != null) {
                sig.disconnect();
            }
        }

        // Caller must be on the GTK main thread. onStateChanged's virtual-thread call sites are
        // wrapped once at the outer fan-out; bind runs on the main thread already.
        public void updateCellIsPlaying(boolean isPlayingNow, NowPlayingState playingState) {
            this.playingState = playingState;
            this.isCurrentlyPlaying = isPlayingNow;
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
        }
    }

    private static class TitleArtistCell extends Box {
        private final Label titleLabel;
        private final Consumer<AppRoute> onNavigate;
        private final ArtistsLinkLabelV3 artistLabel;
        private final RoundedAlbumArtV2 albumArt;
        private GSongInfo gSong;
        private volatile NowPlayingState playingState = NowPlayingState.NONE;
        private ListItem listItem;
        private boolean itemAndPositionIsPlaying;
        @Nullable volatile GPlaylistEntry boundEntry;

        TitleArtistCell(AppManager appManager, Consumer<AppRoute> onNavigate) {
            super(HORIZONTAL, 8);
            this.onNavigate = onNavigate;
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            var albumArtBox = new Clamp();
            albumArtBox.setMaximumSize(48);
            this.albumArt = new RoundedAlbumArtV2(appManager, 48);
            this.albumArt.setValign(CENTER);
            albumArtBox.setChild(this.albumArt);
            this.append(albumArtBox);

            this.titleLabel = new Label();
            this.titleLabel.setHalign(START);
            this.titleLabel.setEllipsize(EllipsizeMode.END);
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setMaxWidthChars(36);

            this.artistLabel = new ArtistsLinkLabelV3(
            artistId -> {
                if (this.gSong == null) {
                    return;
                }
                if (artistId == null) {
                    return;
                }
                this.onNavigate.accept(new AppRoute.RouteArtistInfo(artistId));
            });
            this.artistLabel.addCssClass(Classes.labelDim.className());
            this.artistLabel.addCssClass(Classes.caption.className());
            this.artistLabel.setHalign(START);
            //this.artistLabel.setSingleLineMode(true);
            //this.artistLabel.setEllipsize(EllipsizeMode.END);

            var inner = new Box(VERTICAL, 2);
            inner.setHexpand(true);
            inner.setVexpand(true);
            inner.setValign(CENTER);
            inner.append(titleLabel);
            inner.append(artistLabel);
            this.append(inner);
        }

        boolean bind(GPlaylistEntry entry, ListItem listItem, Optional<PlayItemId> playingItem) {
            this.gSong = entry.gSong();
            this.listItem = listItem;
            this.boundEntry = entry;
            var info = entry.gSong().getSongInfo();
            this.titleLabel.setLabel(info.title());
            if (info.artists().isPresent() && !info.artists().get().isEmpty()) {
                //log.info("Song {} has artists={} albumArtists={}", info.id(), info.artists().get(), info.albumArtists().orElseGet(List::of));
            }
            var artists = info.artists().orElse(List.of());
            if (artists.isEmpty()) {
                //log.info("Song {} has main={} artists={} albumArtists={}", info.id(), info.mainArtist(), info.artists().get(), info.albumArtists().orElseGet(List::of));
                artists = List.of(info.mainArtist());
            }
            this.artistLabel.setArtists(artists);
            this.albumArt.update(info.coverArt().orElse(null));
            boolean isPlaying = isPlayingEntry(entry.playItemId(), playingItem);
            updateRow(isPlaying);
            return isPlaying;
        }

        private static boolean isPlayingEntry(PlayItemId entryItem, Optional<PlayItemId> playingItem) {
            return playingItem.map(p -> p.queueItemId().equals(entryItem.queueItemId())).orElse(false);
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            this.boundEntry = null;
            this.titleLabel.removeCssClass(Classes.colorAccent.className());
        }

        // Caller must be on the GTK main thread (see updateCellIsPlaying's note).
        public void updateRow(boolean positionIsPlaying) {
            if (this.gSong == null) {
                return;
            }
            this.itemAndPositionIsPlaying = positionIsPlaying;
            if (positionIsPlaying) {
                this.titleLabel.addCssClass(Classes.colorAccent.className());
            } else {
                this.titleLabel.removeCssClass(Classes.colorAccent.className());
            }
        }
    }

    private static class AlbumCell extends Box {
        private final ClickLabel albumLabel;
        private final Consumer<AppRoute> onNavigate;
        @Nullable private GSongInfo gSong;
        @Nullable volatile GPlaylistEntry boundEntry;
        @Nullable volatile ListItem listItem;

        AlbumCell(Consumer<AppRoute> onNavigate) {
            super(HORIZONTAL, 0);
            this.onNavigate = onNavigate;
            this.setHalign(START);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);
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
                this.onNavigate.accept(new AppRoute.RouteAlbumInfo(albumId));
            }
            );
            this.albumLabel.addCssClass(Classes.labelDim.className());
            this.albumLabel.addCssClass(Classes.caption.className());
            this.albumLabel.setSingleLineMode(true);
            this.albumLabel.setEllipsize(EllipsizeMode.END);
            this.append(albumLabel);
        }

        void bind(ListItem listItem, GPlaylistEntry entry) {
            this.listItem = listItem;
            this.boundEntry = entry;
            this.gSong = entry.gSong();

            var album = gSong.getSongInfo().album();
            this.albumLabel.setLabel(album != null ? album : "");
        }

        public void unbind() {
            this.boundEntry = null;
            this.listItem = null;
            this.gSong = null;
            this.albumLabel.setLabel("");
        }
    }

    private class ActionCell extends Box {
        private final SongDownloadStatusIcon downloadIcon;
        private final StarButtonSimple starButton;
        private final Button menuButton;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        private GSongInfo gSong;
        private final AtomicReference<SignalConnection<?>> favoriteSignal = new AtomicReference<>();
        private final AtomicReference<SignalConnection<?>> downloadSignal = new AtomicReference<>();
        @Nullable volatile GPlaylistEntry boundEntry;
        @Nullable volatile ListItem listItem;

        ActionCell(Function<PlayerAction, CompletableFuture<Void>> onAction) {
            super(HORIZONTAL, 4);
            this.onAction = onAction;
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);

            this.downloadIcon = new SongDownloadStatusIcon();
            this.downloadIcon.setValign(CENTER);
            this.downloadIcon.addCssClass(Classes.labelDim.className());
            this.append(downloadIcon);

            this.starButton = new StarButtonSimple(
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
            this.starButton.setHalign(CENTER);
            this.append(starButton);

            this.menuButton = Button.builder()
                    .setIconName("view-more-symbolic")
                    .setCssClasses(new String[]{"flat", "circular"})
                    .setValign(CENTER)
                    .build();
            this.menuButton.onClicked(() -> {
                var item = this.listItem;
                if (item == null) {
                    return;
                }
                var entry = this.boundEntry;
                if (entry == null) {
                    return;
                }
                selectionModel.setSelected(item.getPosition());
                var popover = buildRowContextMenu(entry);
                popover.setParent(this.menuButton);
                popover.onClosed(() -> popover.unparent());
                popover.popup();
            });
            this.append(menuButton);
        }

        void bind(ListItem listItem, GPlaylistEntry entry) {
            this.listItem = listItem;
            this.boundEntry = entry;
            this.gSong = entry.gSong();
            this.starButton.setStarredAt(gSong.getSongInfo().starred());
            this.downloadIcon.updateDownloadState(gSong.getDownloadStateEnum());
            var conn = gSong.onNotify(
                    GSongInfo.Signal.IS_FAVORITE.getId(), _ -> {
                        this.starButton.setStarredAt(this.gSong.getSongInfo().starred());
                    }
            );
            var old = favoriteSignal.getAndSet(conn);
            if (old != null) {
                old.disconnect();
            }
            var dlConn = gSong.onNotify(
                    GSongInfo.Signal.DOWNLOAD_STATE.getId(), _ -> {
                        var g = this.gSong;
                        if (g != null) {
                            this.downloadIcon.updateDownloadState(g.getDownloadStateEnum());
                        }
                    }
            );
            var oldDl = downloadSignal.getAndSet(dlConn);
            if (oldDl != null) {
                oldDl.disconnect();
            }
        }

        void unbind() {
            var sig = favoriteSignal.getAndSet(null);
            if (sig != null) {
                sig.disconnect();
            }
            var dlSig = downloadSignal.getAndSet(null);
            if (dlSig != null) {
                dlSig.disconnect();
            }
            this.boundEntry = null;
            this.listItem = null;
            this.gSong = null;
        }
    }

}