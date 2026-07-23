package org.subsound.ui.components;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.CallbackAnimationTarget;
import org.gnome.adw.Easing;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.adw.ResponseAppearance;
import org.gnome.adw.StatusPage;
import org.gnome.adw.TimedAnimation;
import org.gnome.gdk.Gdk;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerFocus;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListTabBehavior;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.PlaylistSimple;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.ui.views.PlaylistListViewV2;
import org.subsound.utils.Utils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.i18n.I18n.tr;
import static org.subsound.i18n.I18n.trn;
import static org.subsound.utils.Utils.cssClasses;
import static org.subsound.utils.Utils.doAsync;

public class PlaylistsListView extends Box {
    private static final Logger log = LoggerFactory.getLogger(PlaylistsListView.class);

    private final AppManager appManager;
    private final ListStore<GPlaylist> listModel;
    private final ListView listView;
    private final NavigationSplitView view;
    private final NavigationPage initialPage;
    private final NavigationPage page1;
    private final PlaylistListViewV2 playlistListView;
    private final PlaylistListViewV2 starredPlaylistView;
    private final NavigationPage playlistPage;
    private final NavigationPage starredPlaylistPage;
    private final Overlay contentOverlay;
    private final Box loadingOverlay;
    private final SingleSelection<GPlaylist> selectionModel;
    private final GSongStore songStore;
    private int currentIndex = 0;
    private String currentPlaylistId = null;
    private static final int FADE_MS = 250;
    private static final String PLAYLIST_PAGE_TAG = "page-2";
    private final AtomicLong loadGeneration = new AtomicLong();
    // Currently running overlay fade, if any. Main-thread only.
    private TimedAnimation overlayAnim = null;

    public PlaylistsListView(AppManager appManager) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.songStore = appManager.getSongStore();
        this.listModel = appManager.getPlaylistsListStore();

        this.playlistListView = new PlaylistListViewV2(appManager, appManager::navigateTo);
        this.playlistListView.setHalign(Align.FILL);
        this.playlistListView.setValign(Align.FILL);

        // Overlay (not Stack) keeps the V2 mapped throughout. The loading affordance is a
        // plain Box whose opacity is driven by an AdwTimedAnimation (see animateOverlayTo)
        // so we get a real done-signal for sequencing. It stays alive on top so clicks
        // pass through (setCanTarget=false).
        this.loadingOverlay = Box.builder().setHexpand(true).setVexpand(true).build();
        this.loadingOverlay.addCssClass("playlist-loading-overlay");
        this.loadingOverlay.setCanTarget(false);
        this.loadingOverlay.setOpacity(0);

        this.contentOverlay = Overlay.builder()
                .setChild(this.playlistListView)
                .setHexpand(true)
                .setVexpand(true)
                .build();
        this.contentOverlay.addOverlay(this.loadingOverlay);

        this.playlistPage = NavigationPage.builder()
                .setTag(PLAYLIST_PAGE_TAG)
                .setChild(this.contentOverlay)
                .setTitle("")
                .setHexpand(true)
                .build();

        // Dedicated starred view bound reactively to the StarredListStore. Avoids the
        // copy-into-ArrayList round-trip and lets star/unstar elsewhere reflect live here.
        this.starredPlaylistView = new PlaylistListViewV2(appManager, appManager::navigateTo);
        this.starredPlaylistView.setHalign(Align.FILL);
        this.starredPlaylistView.setValign(Align.FILL);
        this.starredPlaylistPage = NavigationPage.builder()
                .setTag("page-2-starred")
                .setChild(this.starredPlaylistView)
                .setTitle(tr("Starred"))
                .setHexpand(true)
                .build();
        this.starredPlaylistView.bindSource(appManager.getStarredList(), buildStarredPlaceholder());

        var b = Box.builder().setValign(Align.CENTER).setHalign(Align.CENTER).build();
        b.append(Label.builder().setLabel(tr("Select a playlist to view")).setCssClasses(cssClasses("title-1")).build());
        var statusPage = StatusPage.builder().setChild(b).build();
        this.initialPage = NavigationPage.builder().setTag("page-2-initial").setChild(statusPage).build();

        this.view = NavigationSplitView.builder()
                .setValign(Align.FILL)
                .setHalign(Align.FILL)
                .setHexpand(true)
                .setVexpand(true)
                .build();

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);
            var row = new PlaylistRowWidget(appManager);
            // Single-click open: opens whichever playlist the (recycled) row currently shows.
            // Attached to the row itself rather than the ListView so it doesn't compete with
            // the ListView's own selection gesture; openPlaylistAt sets the selection too.
            var rowClick = new GestureClick();
            rowClick.setButton(Gdk.BUTTON_PRIMARY);
            rowClick.onReleased((nPress, x, y) -> {
                if (nPress != 1) {
                    return;
                }
                openPlaylist(row.getPlaylist());
            });
            row.addController(rowClick);
            listitem.setChild(row);
        });

        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            var item = (GPlaylist) listitem.getItem();
            if (item == null) {
                return;
            }
            var child = listitem.getChild();
            if (child instanceof PlaylistRowWidget row) {
                row.bind(item);
            }
        });

        factory.onUnbind(object -> {
            ListItem listitem = (ListItem) object;
            var child = listitem.getChild();
            if (child instanceof PlaylistRowWidget row) {
                row.unbind();
            }
        });

        factory.onTeardown(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setChild(null);
        });

        this.selectionModel = new SingleSelection<>(this.listModel);
        this.selectionModel.setAutoselect(true);
        this.selectionModel.setCanUnselect(false);

        this.listView = ListView.builder()
                .setShowSeparators(false)
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(false)
                // false so hovering a row shows the theme's neutral prelight instead of the
                // accent selected-row colour (single-click-activate == select-on-hover). We
                // re-add single-click "open" via the click gesture + selection listener below.
                .setSingleClickActivate(false)
                .setFactory(factory)
                .setModel(selectionModel)
                .setTabBehavior(ListTabBehavior.ITEM)
                .build();

        // Fires on Enter / double-click now that single-click-activate is off. Single-click
        // open is handled by a per-row GestureClick in the factory (see onSetup below): an
        // ancestor-level gesture on the ListView competes with the row's own selection
        // gesture, so we open from the row itself and set the selection there.
        var activateSignal = this.listView.onActivate(this::openPlaylistAt);

        // Keyboard navigation (or any transient selection) moves the highlight off the
        // actively-open playlist. Snap it back to the open one when the pointer or focus
        // leaves the list, so the selected row always reflects what's actually open.
        var motionController = new EventControllerMotion();
        motionController.onLeave(() -> this.selectionModel.setSelected(this.currentIndex));
        this.listView.addController(motionController);

        var focusController = new EventControllerFocus();
        focusController.onLeave(() -> this.selectionModel.setSelected(this.currentIndex));
        this.listView.addController(focusController);

        this.listModel.onItemsChanged((position, nRemoved, nAdded) -> {
            // Only act when the net item count decreases (real deletion).
            // nRemoved == nAdded is an in-place update (e.g. song-count refresh) — ignore it.
            if (nRemoved <= nAdded) {
                return;
            }
            int total = this.listModel.getNItems();
            if (total == 0) {
                return;
            }
            // Find where the currently displayed playlist is now (by ID, not index).
            var id = this.currentPlaylistId;
            if (id != null) {
                for (int i = 0; i < total; i++) {
                    var item = this.listModel.getItem(i);
                    if (item != null && id.equals(item.getId())) {
                        // Still present — just keep currentIndex in sync.
                        this.currentIndex = i;
                        return;
                    }
                }
            }
            // Current playlist was removed: select next, or last if at end.
            int newIndex = Math.min((int) position, total - 1);
            this.currentIndex = newIndex;
            this.selectionModel.setSelected(newIndex);
            var gPlaylist = this.listModel.getItem(newIndex);
            if (gPlaylist != null) {
                this.currentPlaylistId = gPlaylist.getId();
                this.setSelectedPlaylist(gPlaylist.getPlaylist());
            }
        });

        this.onDestroy(() -> {
            activateSignal.disconnect();
        });

        var playlistScrollView = ScrolledWindow.builder()
                .setChild(this.listView)
                .setHexpand(true)
                .setVexpand(true)
                .build();

        var headerLabel = Label.builder()
                .setLabel(tr("Library"))
                .setHalign(START)
                .setHexpand(true)
                .setCssClasses(cssClasses("title-3"))
                .build();

        var newPlaylistButton = Button.builder()
                .setIconName(Icons.ListAdd.getIconName())
                .setTooltipText(tr("New playlist"))
                .setValign(CENTER)
                .build();
        newPlaylistButton.addCssClass("flat");
        newPlaylistButton.onClicked(() -> showNewPlaylistDialog());

        var headerBox = new Box(HORIZONTAL, 0);
        headerBox.setMarginTop(8);
        headerBox.setMarginBottom(4);
        headerBox.setMarginStart(12);
        headerBox.setMarginEnd(4);
        headerBox.append(headerLabel);
        headerBox.append(newPlaylistButton);

        var sidebarBox = new Box(VERTICAL, 0);
        sidebarBox.append(headerBox);
        sidebarBox.append(playlistScrollView);
        sidebarBox.addCssClass(Classes.background.className());
        sidebarBox.addCssClass("library-sidebar");


        this.page1 = NavigationPage.builder()
                .setTag("page-1")
                .setChild(sidebarBox)
                .setTitle(tr("Playlists"))
                .build();
        this.view.setSidebar(this.page1);
        this.view.setMaxSidebarWidth(300);
        this.view.setShowContent(true);
        this.view.setHexpand(true);
        this.view.setVexpand(true);
        this.view.setHalign(Align.FILL);
        this.view.setValign(Align.BASELINE_FILL);
        this.view.setContent(this.initialPage);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(view);
    }

    // Open the given playlist (single-click path from a row's gesture). Resolves its current
    // model index so selection follows, then delegates to the id-guarded openPlaylistAt.
    private void openPlaylist(GPlaylist gPlaylist) {
        if (gPlaylist == null) {
            return;
        }
        var id = gPlaylist.getId();
        for (int i = 0; i < this.listModel.getNItems(); i++) {
            var item = this.listModel.getItem(i);
            if (item != null && id.equals(item.getId())) {
                openPlaylistAt(i);
                return;
            }
        }
    }

    // Open the playlist at the given model index, unless it is already the open one
    // (keeps index in sync but avoids reloading — a double-click triggers both the
    // single-click open and onActivate, and this makes the second a no-op).
    private void openPlaylistAt(int index) {
        var gPlaylist = this.listModel.getItem(index);
        if (gPlaylist == null) {
            return;
        }
        if (gPlaylist.getId().equals(this.currentPlaylistId)) {
            this.currentIndex = index;
            return;
        }
        this.currentIndex = index;
        this.currentPlaylistId = gPlaylist.getId();
        this.selectionModel.setSelected(index);
        var playlist = gPlaylist.getPlaylist();
        log.info("openPlaylistAt: {} {}", index, playlist.name());
        this.setSelectedPlaylist(playlist);
    }

    private static PlaylistSimple buildStarredPlaceholder() {
        // Bound at construction, before the playlists list has been populated. The actual
        // PlaylistSimple shown in the title/UI comes from setSelectedPlaylist; this is
        // just what bindSource hands to setSongs for the initial fill.
        var now = Instant.now();
        return new PlaylistSimple(
                PlaylistsStore.STARRED_ID,
                tr("Starred"),
                PlaylistKind.STARRED,
                Optional.empty(),
                0,
                now,
                now
        );
    }

    public void preselect(String playlistId) {
        for (int i = 0; i < this.listModel.getNItems(); i++) {
            var gPlaylist = this.listModel.getItem(i);
            if (gPlaylist != null && playlistId.equals(gPlaylist.getId())) {
                this.currentIndex = i;
                this.currentPlaylistId = gPlaylist.getId();
                this.selectionModel.setSelected(i);
                this.setSelectedPlaylist(gPlaylist.getPlaylist());
                return;
            }
        }
        log.info("preselect: playlistId={} not found in listModel (size={})", playlistId, this.listModel.getNItems());
    }

    private void setSelectedPlaylist(PlaylistSimple playlist) {
        // Starred is bound reactively to its source store — no fetch, no splice;
        // just swap pages so the user gets an instant transition.
        if (playlist.kind() == PlaylistKind.STARRED) {
            Utils.runOnMainThread(() -> {
                this.starredPlaylistPage.setTitle(playlist.name());
                this.view.setContent(this.starredPlaylistPage);
            });
            return;
        }

        long gen = this.loadGeneration.incrementAndGet();

        // Re-selecting the playlist that is already displayed: dimming out a frame of the
        // exact content we'd fade back in looks broken. Change-detect on the sidebar's
        // metadata (songCount + changedAt, kept live by PlaylistsStore) instead of fetching
        // and diffing 15k song ids — if it matches what we last loaded, swap instantly and
        // skip the fetch entirely. A server-side change the sidebar hasn't seen yet is
        // missed, but then the content is exactly as stale as the sidebar itself.
        boolean unchanged = this.playlistListView.getCurrentPlaylist()
                .map(p -> p.id().equals(playlist.id())
                        && p.songCount() == playlist.songCount()
                        && p.changedAt().equals(playlist.changedAt()))
                .orElse(false);
        if (unchanged) {
            Utils.runOnMainThread(() -> {
                stopOverlayAnim();
                this.loadingOverlay.setOpacity(0);
                this.playlistPage.setTitle(playlist.name());
                this.view.setContent(this.playlistPage);
            });
            return;
        }

        var dataFuture = doAsync(() -> switch (playlist.kind()) {
            case NORMAL -> this.appManager.useClient(cl -> cl.getPlaylist(playlist.id())).songs();
            case DOWNLOADED -> this.appManager.listDownloadedSongInfos();
            case STARRED -> throw new IllegalStateException("handled above");
        });
        loadCovered(gen, playlist, dataFuture);
    }

    /**
     * Swap to the playlist page and fill it with {@code dataFuture}'s songs, keeping the
     * loading overlay opaque over all the heavy splice work. The fades are only smooth if
     * they never overlap main-thread work: fade in while the fetch runs off-thread (idle
     * main loop → smooth tween), splice rows only once the overlay is fully opaque
     * (dropped frames are invisible under the cover), and start the fade-out one idle
     * after the last chunk so the ColumnView's layout/first paint also happens under cover.
     */
    private void loadCovered(long gen, PlaylistSimple playlist, CompletableFuture<List<SongInfo>> dataFuture) {
        var overlayOpaque = new CompletableFuture<Void>();
        Utils.runOnMainThread(() -> {
            this.playlistPage.setTitle(playlist.name());
            var content = this.view.getContent();
            boolean pageAlreadyVisible = content != null && PLAYLIST_PAGE_TAG.equals(content.getTag());
            if (!pageAlreadyVisible) {
                // Coming from another page: the shared view still shows some other
                // playlist's rows. Cover instantly — no tween — so the stale rows are
                // never shown.
                stopOverlayAnim();
                this.loadingOverlay.setOpacity(1);
                this.view.setContent(this.playlistPage);
                // One idle so the covered swap gets painted before splice work starts
                // blocking the main loop.
                Utils.runOnMainThread(() -> overlayOpaque.complete(null));
            } else {
                // Fade in over the still-visible old content. The fetch runs off-thread,
                // so the main loop is idle and the tween gets every frame; the done
                // signal (immediate if system animations are off) gates the splice work.
                this.view.setContent(this.playlistPage);
                animateOverlayTo(1).thenRun(() -> overlayOpaque.complete(null));
            }
        });

        overlayOpaque.thenCombine(dataFuture, (_, data) -> data)
                .thenCompose(data -> {
                    // A newer selection superseded this load — leave the view to it.
                    if (this.loadGeneration.get() != gen) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    var songs = data.stream().map(songStore::newInstance).toList();
                    return this.playlistListView.setSongs(songs, playlist);
                })
                // whenComplete (not thenRun): thenRun only fires on normal completion, so a
                // fetch or setSongs future that completes exceptionally — or an internal
                // throw — would leave the opaque loading overlay stuck over the whole view
                // (blank title + headers). Always lift the curtain, and log the exception.
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.warn("setSelectedPlaylist failed for {} ({})", playlist.id(), playlist.kind(), ex);
                    }
                    // A newer selection owns the overlay now — don't fade it out mid-load.
                    if (this.loadGeneration.get() != gen) {
                        return;
                    }
                    // runOnMainThread uses PRIORITY_DEFAULT_IDLE, which runs after GTK's
                    // redraw: the freshly filled ColumnView is laid out and painted under
                    // the opaque overlay before the fade-out's first frame.
                    Utils.runOnMainThread(() -> animateOverlayTo(0));
                });
    }

    /**
     * Drive the loading overlay's opacity to {@code to} with an AdwTimedAnimation,
     * replacing any fade already running. The returned future completes on the
     * animation's done signal — which fires immediately when system animations are
     * disabled, so callers never wait out a fade that isn't happening. Main-thread only.
     */
    private CompletableFuture<Void> animateOverlayTo(double to) {
        stopOverlayAnim();
        var done = new CompletableFuture<Void>();
        var target = new CallbackAnimationTarget(this.loadingOverlay::setOpacity);
        var anim = new TimedAnimation(this.loadingOverlay, this.loadingOverlay.getOpacity(), to, FADE_MS, target);
        anim.setEasing(Easing.EASE_IN_OUT_CUBIC);
        anim.onDone(() -> done.complete(null));
        this.overlayAnim = anim;
        anim.play();
        return done;
    }

    private void stopOverlayAnim() {
        var anim = this.overlayAnim;
        if (anim != null) {
            anim.pause();
            this.overlayAnim = null;
        }
    }

    private void showNewPlaylistDialog() {
        var entry = Entry.builder()
                .setPlaceholderText(tr("Playlist name"))
                .setActivatesDefault(true)
                .build();

        var dialog = AlertDialog.builder()
                .setTitle(tr("Create Playlist"))
                .setBody(tr("Enter a name for the new playlist"))
                .build();
        // TRANSLATORS: "_" marks the mnemonic key
        dialog.addResponse("cancel", tr("_Cancel"));
        dialog.addResponse("create", tr("_Create"));
        dialog.setResponseAppearance("create", ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse("create");
        dialog.setCloseResponse("cancel");
        dialog.setResponseEnabled("create", false);
        dialog.setExtraChild(entry);
        var sig = entry.onChanged(() -> {
            var text = entry.getText();
            text = text == null ? "" : text.strip().trim();
            dialog.setResponseEnabled("create", !text.isBlank());
        });

        dialog.onResponse("", response -> {
            try {
                if ("create".equals(response)) {
                    var name = entry.getText().strip().trim();
                    if (!name.isEmpty()) {
                        appManager.handleAction(new PlayerAction.CreatePlaylist(name, List.of()));
                    }
                }
            } finally {
                sig.disconnect();
            }
        });
        dialog.present(this);
    }

    private static class PlaylistRowWidget extends Box {
        private static final int ICON_SIZE = 48;

        private final AppManager appManager;
        private final Box prefixBox;
        private final RoundedAlbumArt prefixArt;
        private final Image prefixIconDownload;
        private final Image prefixIconStar;
        private final Label titleLabel;
        private final Label subtitleLabel;
        private final Image subtitleCheckmark;
        private final Image offlineBadge;
        private GPlaylist gPlaylist;
        private SignalConnection<NotifyCallback> notifySignal;

        public PlaylistRowWidget(AppManager appManager) {
            super(HORIZONTAL, 12);
            this.appManager = appManager;

            this.setMarginTop(8);
            this.setMarginBottom(8);
            this.setMarginStart(12);
            this.setMarginEnd(12);

            // Prefix box for icon/cover art
            this.prefixBox = new Box(HORIZONTAL, 0);
            this.prefixBox.setHalign(CENTER);
            this.prefixBox.setValign(CENTER);
            this.prefixBox.setSizeRequest(ICON_SIZE, ICON_SIZE);

            // Create all three prefix widgets once; toggle visibility
            this.prefixArt = new RoundedAlbumArt(Optional.empty(), appManager, ICON_SIZE);
            this.prefixArt.setVisible(false);
            this.prefixArt.setHalign(CENTER);
            this.prefixArt.setValign(CENTER);

            this.prefixIconDownload = Image.fromIconName(Icons.FolderDownload.getIconName());
            this.prefixIconDownload.setPixelSize(24);
            this.prefixIconDownload.setHalign(CENTER);
            this.prefixIconDownload.setValign(CENTER);
            this.prefixIconDownload.setSizeRequest(ICON_SIZE, ICON_SIZE);
            this.prefixIconDownload.setVisible(false);

            this.prefixIconStar = Image.fromIconName(Icons.Starred.getIconName());
            this.prefixIconStar.setPixelSize(24);
            this.prefixIconStar.setHalign(CENTER);
            this.prefixIconStar.setValign(CENTER);
            this.prefixIconStar.setSizeRequest(ICON_SIZE, ICON_SIZE);
            this.prefixIconStar.addCssClass(Classes.starred.className());
            this.prefixIconStar.setVisible(false);

            this.prefixBox.append(prefixArt);
            this.prefixBox.append(prefixIconDownload);
            this.prefixBox.append(prefixIconStar);

            // Content box for title and subtitle
            var contentBox = new Box(VERTICAL, 2);
            contentBox.setHalign(START);
            contentBox.setValign(CENTER);
            contentBox.setHexpand(true);

            this.titleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .build();
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setEllipsize(EllipsizeMode.END);

            this.subtitleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .setCssClasses(cssClasses(Classes.labelDim.className(), Classes.caption.className()))
                    .build();
            this.subtitleLabel.setSingleLineMode(true);

            this.subtitleCheckmark = Image.fromIconName(Icons.CheckmarkCircle.getIconName());
            this.subtitleCheckmark.setPixelSize(14);
            this.subtitleCheckmark.setValign(CENTER);
            this.subtitleCheckmark.addCssClass(Classes.colorSuccess.className());
            this.subtitleCheckmark.setVisible(false);

            var subtitleRow = new Box(HORIZONTAL, 4);
            subtitleRow.setHalign(START);
            subtitleRow.append(subtitleCheckmark);
            subtitleRow.append(subtitleLabel);

            contentBox.append(titleLabel);
            contentBox.append(subtitleRow);

            // Trailing "available offline" badge; contentBox is hexpand so this sits at the edge.
            this.offlineBadge = Image.fromIconName(Icons.FolderDownload.getIconName());
            this.offlineBadge.setPixelSize(16);
            this.offlineBadge.setValign(CENTER);
            this.offlineBadge.addCssClass(Classes.labelDim.className());
            this.offlineBadge.setTooltipText(tr("Available offline"));
            this.offlineBadge.setVisible(false);

            this.append(prefixBox);
            this.append(contentBox);
            this.append(offlineBadge);
        }

        public GPlaylist getPlaylist() {
            return this.gPlaylist;
        }

        public void bind(GPlaylist gPlaylist) {
            this.gPlaylist = gPlaylist;
            updateFromPlaylist(this.gPlaylist.getPlaylist());

            // Connect to notify signal to update when playlist data changes
            this.notifySignal = this.gPlaylist.onNotify("name", _ -> {
                log.info("gPlaylist.onNotify: name {}", this.gPlaylist.getPlaylist());
                updateFromPlaylist(this.gPlaylist.getPlaylist());
            });
        }

        private void updateFromPlaylist(PlaylistSimple playlist) {
            this.titleLabel.setLabel(playlist.name());
            this.subtitleCheckmark.setVisible(false);

            // Offline badge: shown for real/Starred playlists the user marked available offline.
            // The Downloaded playlist is itself the offline bucket, so never badge it.
            boolean showOffline = playlist.kind() != PlaylistKind.DOWNLOADED
                    && this.gPlaylist != null
                    && this.gPlaylist.isKeepOffline();
            this.offlineBadge.setVisible(showOffline);

            if (playlist.kind() == PlaylistKind.DOWNLOADED) {
                this.prefixArt.setVisible(false);
                this.prefixIconStar.setVisible(false);
                this.prefixIconDownload.setVisible(true);

                var counts = this.gPlaylist != null ? this.gPlaylist.getDownloadCounts() : null;
                if (counts != null && !counts.isEmpty() && !counts.allDone()) {
                    // In progress — show "completed / total"
                    this.subtitleLabel.setLabel(counts.completed() + " / " + counts.total());
                } else if (counts != null && counts.allDone()) {
                    // All done — green checkmark next to subtitle
                    this.subtitleLabel.setLabel(trn("%d item", "%d items", playlist.songCount()).formatted(playlist.songCount()));
                    this.subtitleCheckmark.setVisible(true);
                } else {
                    // Empty queue
                    this.subtitleLabel.setLabel(trn("%d item", "%d items", playlist.songCount()).formatted(playlist.songCount()));
                }
            } else if (playlist.kind() == PlaylistKind.STARRED) {
                this.subtitleLabel.setLabel(trn("%d item", "%d items", playlist.songCount()).formatted(playlist.songCount()));
                this.prefixArt.setVisible(false);
                this.prefixIconDownload.setVisible(false);
                this.prefixIconStar.setVisible(true);
            } else {
                this.subtitleLabel.setLabel(trn("%d item", "%d items", playlist.songCount()).formatted(playlist.songCount()));
                this.prefixIconDownload.setVisible(false);
                this.prefixIconStar.setVisible(false);
                this.prefixArt.setVisible(true);
                this.prefixArt.update(playlist.coverArtId());
            }
        }

        public void unbind() {
            var notifySig = this.notifySignal;
            if (notifySig != null) {
                notifySig.disconnect();
                this.notifySignal = null;
            }
            this.gPlaylist = null;
        }
    }
}