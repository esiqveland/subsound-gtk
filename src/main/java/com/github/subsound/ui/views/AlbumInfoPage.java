package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.AppManager.AlbumInfo;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.app.state.PlaylistsStore.GPlaylist;
import com.github.subsound.integration.ServerClient.PlaylistKind;
import com.github.subsound.app.state.PlayerAction.PlayAndReplaceQueue;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.Classes;
import com.github.subsound.ui.components.Icons;
import com.github.subsound.ui.components.NowPlayingOverlayIcon;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.PlaylistChooser;
import com.github.subsound.ui.components.RoundedAlbumArt;
import com.github.subsound.ui.components.SongDownloadStatusIcon;
import com.github.subsound.ui.components.StarButton;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.ui.models.GDownloadState;
import com.github.subsound.ui.models.GSongInfo.Signal;
import com.github.subsound.utils.ImageUtils;
import com.github.subsound.utils.Utils;
import org.gnome.adw.ActionRow;
import org.gnome.gdk.Display;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StateFlags;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.github.subsound.utils.Utils.addHover;
import static com.github.subsound.utils.Utils.cssClasses;
import static com.github.subsound.utils.Utils.formatBytesSI;
import static com.github.subsound.utils.Utils.formatDurationMedium;
import static org.gnome.gtk.Align.BASELINE_FILL;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class AlbumInfoPage extends Box {
    private final AppManager appManager;

    //private final ArtistInfo artistInfo;
    private final AlbumInfo info;

    private final Box headerBox;
    private final ScrolledWindow scroll;
    private final Box mainContainer;
    private final Box albumInfoBox;
    private final ListBox listView;
    private final List<AlbumSongActionRow> rows;
    private final Widget artistImage;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Popover playlistPopover;
    private static final int COVER_SIZE = 300;
    private static final CssProvider COLOR_PROVIDER = CssProvider.builder().build();
    private static final AtomicBoolean isProviderInit = new AtomicBoolean(false);

    public static class AlbumSongActionRow extends ActionRow {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AlbumSongActionRow.class);
        private final AppManager appManager;
        private final AlbumInfo albumInfo;
        public final GSongInfo gSongInfo;
        public final SongInfo songInfo;
        private final int index;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        public final NowPlayingOverlayIcon icon;
        private final StarButton starredButton;
        private final SongDownloadStatusIcon downloadStatusIcon = new SongDownloadStatusIcon();
        private SignalConnection<NotifyCallback> signalPlaying;
        private SignalConnection<NotifyCallback> signalFavorited;
        private SignalConnection<NotifyCallback> signalDownloadStatus;

        public AlbumSongActionRow(
                AppManager appManager,
                AlbumInfo albumInfo,
                int index,
                GSongInfo gSongInfo,
                Function<PlayerAction, CompletableFuture<Void>> onAction
        ) {
            super();
            this.appManager = appManager;
            this.albumInfo = albumInfo;
            this.index = index;
            this.gSongInfo = gSongInfo;
            this.songInfo = gSongInfo.getSongInfo();
            this.onAction = onAction;
            this.addCssClass(Classes.rounded.className());
            this.addCssClass("AlbumSongActionRow");
            this.setUseMarkup(false);
            this.setActivatable(true);
            this.setFocusable(true);
            this.setFocusOnClick(true);
            this.setHexpand(true);

            var suffix = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();

            starredButton = new StarButton(
                    songInfo.starred(),
                    newValue -> {
                        var action = newValue ? new PlayerAction.Star(songInfo) : new PlayerAction.Unstar(songInfo);
                        return this.onAction.apply(action);
                    }
            );

            var hoverBox = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setSpacing(8)
                    .build();

            var menuPopover = getPopover();
            var menuButton = MenuButton.builder()
                    .setIconName(Icons.OpenMenu.getIconName())
                    .setPopover(menuPopover)
                    .build();
            menuButton.addCssClass("flat");
            menuButton.addCssClass("circular");

            var fileFormatLabel = Optional.ofNullable(songInfo.suffix())
                    .filter(fileExt -> !fileExt.isBlank())
                    .map(fileExt -> Button.builder()
                            .setLabel(fileExt)
                            .setSensitive(false)
                            .setVexpand(false)
                            .setValign(CENTER)
                            .setCssClasses(cssClasses("dim-label"))
                            .build()
                    );

            var fileSizeLabel = infoLabel(formatBytesSI(songInfo.size()), cssClasses("dim-label"));
            var bitRateLabel = songInfo.bitRate()
                    .map(bitRate -> infoLabel("%d kbps".formatted(bitRate), cssClasses("dim-label")));
            bitRateLabel.ifPresent(hoverBox::append);
            fileFormatLabel.ifPresent(hoverBox::append);
            hoverBox.append(fileSizeLabel);

            var revealer = Revealer.builder()
                    .setChild(hoverBox)
                    .setRevealChild(false)
                    .setTransitionType(RevealerTransitionType.CROSSFADE)
                    .build();

            suffix.append(revealer);
            suffix.append(downloadStatusIcon);
            var starredButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(CENTER).build();
            starredButtonBox.append(starredButton);
            suffix.append(starredButtonBox);
            var menuButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(CENTER).build();
            menuButtonBox.append(menuButton);
            suffix.append(menuButtonBox);

            String durationString = Utils.formatDurationShortest(songInfo.duration());
            String subtitle = durationString;

            Label songNumberLabel = Label.builder()
                    .setLabel(songInfo.trackNumber().map(String::valueOf).orElse(""))
                    .setWidthChars(2)
                    .setMaxWidthChars(2)
                    .setSingleLineMode(true)
                    .setJustify(Justification.RIGHT)
                    .setEllipsize(EllipsizeMode.START)
                    .setCssClasses(cssClasses("dim-label", "numeric"))
                    .build();
            icon = new NowPlayingOverlayIcon(24, songNumberLabel);
            var isHoverActive = new AtomicBoolean(false);

            addHover(
                    this,
                    () -> {
                        isHoverActive.set(true);
                        revealer.setRevealChild(true);
                        this.icon.setIsHover(true);
                    },
                    () -> {
                        isHoverActive.set(false);
                        var focused = this.hasFocus() || menuButton.hasFocus();
                        revealer.setRevealChild(focused);
                        this.icon.setIsHover(false);
                    }
            );
            this.onStateFlagsChanged(flags -> {
                var hasFocus = menuButton.hasFocus() || flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
                var hasHover = isHoverActive.get();
                revealer.setRevealChild(hasFocus || hasHover);
                this.icon.setIsHover(hasFocus || hasHover);
            });

            this.onMap(() -> {
                signalPlaying = this.gSongInfo.onNotify(
                        GSongInfo.Signal.IS_PLAYING.getId(),
                        cb -> {
                            log.info("{}: isPlaying={}", this.gSongInfo.getTitle(), this.gSongInfo.getIsPlaying());
                            this.updateUI();
                        }
                );
                signalFavorited = this.gSongInfo.onNotify(
                        GSongInfo.Signal.IS_FAVORITE.getId(),
                        cb -> this.updateUI()
                );
                signalDownloadStatus = this.gSongInfo.onNotify(
                        Signal.DOWNLOAD_STATE.getId(),
                        cb -> this.updateUI()
                );
                this.updateUI();
            });
            this.onUnmap(() -> {
                //log.info("disconnect gSongInfo signals");
                if (signalPlaying != null) {
                    signalPlaying.disconnect();
                    signalPlaying = null;
                }
                if (signalFavorited != null) {
                    signalFavorited.disconnect();
                    signalFavorited = null;
                }
                if (signalDownloadStatus != null) {
                    signalDownloadStatus.disconnect();
                    signalDownloadStatus = null;
                }
            });
            this.addPrefix(icon);
            this.addSuffix(suffix);
            this.setTitle(songInfo.title());
            this.setSubtitle(subtitle);
        }

        private void updateUI() {
            Utils.runOnMainThread(() -> {
                this.updateRow(new RowState(this.gSongInfo.getIsPlaying(), this.gSongInfo.getIsStarred(), this.gSongInfo.getDownloadStateEnum()));
            });
        }

        record RowState(boolean isPlaying, boolean isStarred, GDownloadState downloadState){}
        private void updateRow(RowState next) {
            boolean isPlaying = next.isPlaying;
            if (isPlaying) {
                this.addCssClass(Classes.colorAccent.className());
                this.icon.setPlayingState(NowPlayingState.PLAYING);
                // TODO: get a richer is-playing state
                //switch (next.player().state()) {
                //    case PAUSED, INIT -> row.icon.setPlayingState(NowPlayingState.PAUSED);
                //    case PLAYING, BUFFERING, READY, END_OF_STREAM -> row.icon.setPlayingState(NowPlayingState.PLAYING);
                //}

            } else {
                this.removeCssClass(Classes.colorAccent.className());
                this.icon.setPlayingState(NowPlayingState.NONE);
            }
            if (next.isStarred != this.starredButton.isStarred()) {
                this.starredButton.setStarredAt(this.gSongInfo.getStarredAt());
            }
            this.downloadStatusIcon.updateDownloadState(next.downloadState);
        }

        private @NonNull Popover getPopover() {
            // Context menu popover with Stack for submenu navigation
            var stack = new Stack();

            // Main menu content
            var menuContent = Box.builder()
                    .setOrientation(VERTICAL)
                    .setSpacing(2)
                    .setMarginTop(4)
                    .setMarginBottom(4)
                    .setMarginStart(4)
                    .setMarginEnd(4)
                    .build();

            var menuPopover = Popover.builder()
                    .setChild(stack)
                    .build();

            var playMenuItem = menuItem("Play");
            playMenuItem.onClicked(() -> {
                menuPopover.popdown();
                int idx = this.index;
                this.onAction.apply(PlayAndReplaceQueue.of(
                        this.albumInfo.songs(),
                        idx
                ));
            });

            var playNextMenuItem = menuItem("Play Next");
            playNextMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.Enqueue(songInfo));
            });

            var addToQueueMenuItem = menuItem("Add to Queue");
            addToQueueMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.EnqueueLast(songInfo));
            });

            var favoriteMenuItem = menuItem(songInfo.isStarred() ? "Unstar" : "Add to Starred");
            favoriteMenuItem.onClicked(() -> {
                menuPopover.popdown();
                var action = songInfo.isStarred()
                        ? new PlayerAction.Unstar(songInfo)
                        : new PlayerAction.Star(songInfo);
                this.onAction.apply(action);
            });

            var addToPlaylistMenuItem = menuItem("Add to Playlist\u2026");
            addToPlaylistMenuItem.onClicked(() -> {
                stack.setVisibleChildName("playlists");
            });

            var downloadMenuItem = menuItem("Download");
            downloadMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.AddToDownloadQueue(songInfo));
            });

            menuContent.append(playMenuItem);
            menuContent.append(playNextMenuItem);
            menuContent.append(addToQueueMenuItem);
            menuContent.append(favoriteMenuItem);
            menuContent.append(addToPlaylistMenuItem);
            menuContent.append(downloadMenuItem);

            // Playlist selection submenu
            var playlistsView = Box.builder()
                    .setOrientation(VERTICAL)
                    .setSpacing(2)
                    .setMarginTop(4)
                    .setMarginBottom(4)
                    .setMarginStart(4)
                    .setMarginEnd(4)
                    .build();

            var backButton = menuItem("\u2190 Back");
            backButton.onClicked(() -> stack.setVisibleChildName("main"));
            playlistsView.append(backButton);
            playlistsView.append(new Separator(org.gnome.gtk.Orientation.HORIZONTAL));

            // Populate playlist buttons from cache
            var playlists = this.appManager.getPlaylistsListStore();
            for (int i = 0; i < playlists.getNItems(); i++) {
                GPlaylist gPlaylist = playlists.getItem(i);
                // Skip synthetic playlists (Starred, Downloaded)
                if (gPlaylist.getPlaylist().kind() != PlaylistKind.NORMAL) {
                    continue;
                }
                String playlistId = gPlaylist.getId();
                String playlistName = gPlaylist.getName();
                var btn = menuItem(playlistName);
                btn.onClicked(() -> {
                    menuPopover.popdown();
                    this.onAction.apply(new PlayerAction.AddToPlaylist(
                            songInfo, playlistId, playlistName
                    ));
                });
                playlistsView.append(btn);
            }

            stack.addNamed(menuContent, "main");
            stack.addNamed(playlistsView, "playlists");
            stack.setVisibleChildName("main");

            // Reset to main menu when popover closes
            menuPopover.onClosed(() -> stack.setVisibleChildName("main"));

            return menuPopover;
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

    }

    public AlbumInfoPage(
            AppManager appManager,
            AlbumInfo info,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.info = info;
        this.onAction = onAction;
        if (isProviderInit.compareAndSet(false, true)) {
            Gtk.styleContextAddProviderForDisplay(Display.getDefault(), COLOR_PROVIDER, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
        }
        this.artistImage = this.info.album().coverArt()
                .map(coverArt -> new RoundedAlbumArt(
                        coverArt,
                        this.appManager,
                        COVER_SIZE
                ))
                .map(albumArt -> {
                    albumArt.setClickable(false);
                    albumArt.addCssClass("album-main-cover");
                    return albumArt;
                })
                .map(artwork -> (Widget) artwork)
                .orElseGet(() -> RoundedAlbumArt.placeholderImage(COVER_SIZE));

        this.listView = ListBox.builder()
                .setValign(START)
                .setHalign(Align.FILL)
                .setHexpand(true)
                // require double click to activate:
                .setActivateOnSingleClick(false)
                .setCssClasses(Classes.darken.add(
                        Classes.rounded
                        //Classes.boxedList
                ))
                .setShowSeparators(false)
                .build();

        this.listView.onRowActivated(row -> {
            var songInfo = this.info.songs().get(row.getIndex());
            System.out.println("AlbumInfoBox: play " + songInfo.getTitle() + " (%s)".formatted(songInfo.getId()));
            this.onAction.apply(new PlayAndReplaceQueue(
                    this.info.songs().stream().map(GSongInfo::getSongInfo).toList(),
                    row.getIndex()
            ));
        });

        this.rows = new ArrayList<>(this.info.songs().size());
        int idx = 0;
        for (var songInfo : this.info.songs()) {
            var row = new AlbumSongActionRow(this.appManager, this.info, idx, songInfo, this.onAction);
            this.rows.add(row);
            this.listView.append(row);
            idx++;
        }

        this.headerBox = Box.builder().setHalign(BASELINE_FILL).setSpacing(0).setValign(START).setOrientation(VERTICAL).setHexpand(true).setVexpand(true).build();
        this.headerBox.addCssClass("album-info-main");

        this.mainContainer = Box.builder().setHalign(BASELINE_FILL).setSpacing(8).setValign(START).setOrientation(VERTICAL).setHexpand(true).setVexpand(true).setMarginBottom(10).setHomogeneous(false).build();
        this.albumInfoBox = Box.builder().setHalign(CENTER).setSpacing(4).setValign(START).setOrientation(VERTICAL).setHexpand(true).setVexpand(true).setMarginBottom(10).build();
        this.albumInfoBox.append(infoLabel(this.info.album().name(), Classes.titleLarge2.add()));
        this.albumInfoBox.append(infoLabel(this.info.album().artistName(), Classes.titleLarge3.add()));
        this.albumInfoBox.append(infoLabel(this.info.album().year().map(String::valueOf).orElse(""), Classes.labelDim.add(Classes.bodyText)));
        this.albumInfoBox.append(infoLabel("%d songs, %s".formatted(this.info.album().songCount(), formatDurationMedium(this.info.album().totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));
        //this.albumInfoBox.append(infoLabel("%s playtime".formatted(formatDurationMedium(this.albumInfo.totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));

        var downloadAllButtonContent = Box.builder()
                .setOrientation(HORIZONTAL)
                .setSpacing(6)
                .build();
        downloadAllButtonContent.append(Label.builder().setLabel("Download all").build());
        downloadAllButtonContent.append(org.gnome.gtk.Image.fromIconName(Icons.FolderDownload.getIconName()));
        var downloadAllButton = Button.builder()
                //.setLabel("Download All")
                //.setIconName(Icons.FolderDownload.getIconName())
                .setChild(downloadAllButtonContent)
                .setHalign(CENTER)
                .build();
        downloadAllButton.addCssClass("flat");
        downloadAllButton.onClicked(() -> {
            var songs = this.info.songs();
            this.onAction.apply(new PlayerAction.AddManyToDownloadQueue(songs));
        });
        this.playlistPopover = buildPlaylistPopover();
        var addToPlaylistButton = MenuButton.builder()
                .setLabel("Add to Playlist\u2026")
                .setIconName(Icons.Playlists.getIconName())
                .setPopover(this.playlistPopover)
                .setHalign(CENTER)
                .build();
        addToPlaylistButton.addCssClass("flat");

        var actionButtonsBox = Box.builder()
                .setOrientation(HORIZONTAL)
                .setHalign(Align.END)
                .setSpacing(8)
                .setMarginBottom(4)
                .build();
        actionButtonsBox.append(downloadAllButton);
        actionButtonsBox.append(addToPlaylistButton);

        this.headerBox.append(this.artistImage);
        this.headerBox.append(this.albumInfoBox);
        this.mainContainer.append(this.headerBox);
        var listHolder = Box.builder().setOrientation(VERTICAL).setHalign(CENTER).setValign(START).build();
        listHolder.append(actionButtonsBox);
        listHolder.append(listView);
        this.mainContainer.append(listHolder);

        this.scroll = ScrolledWindow.builder().setChild(mainContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(this.scroll);
        this.onMap(() -> this.appManager.getThumbnailCache().loadPixbuf(this.info.album().coverArt().get(), COVER_SIZE).thenAccept(this::switchPallete));
    }

    private PlaylistChooser buildPlaylistPopover() {
        return new com.github.subsound.ui.components.PlaylistChooser(
                this.appManager.getPlaylistsListStore(),
                (playlistId, playlistName) -> {
                    var songs = this.info.songs();
                    this.onAction.apply(new PlayerAction.AddManyToPlaylist(
                            songs, playlistId, playlistName
                    ));
                }
        );
    }

    private void switchPallete(ThumbnailCache.CachedTexture cachedTexture) {
        StringBuilder colors = new StringBuilder();
        List<ImageUtils.ColorValue> palette = cachedTexture.palette();
        for (int i = 0; i < palette.size(); i++) {
            ImageUtils.ColorValue colorValue = palette.get(i);
            colors.append("@define-color background_color_%d %s;\n".formatted(i, colorValue.rgba().toString()));
        }
        COLOR_PROVIDER.loadFromString(colors.toString());
    }

    public static Label infoLabel(String label, String[] cssClazz) {
        return Label.builder().setCssClasses(cssClazz).setUseMarkup(false).setEllipsize(EllipsizeMode.END).setLabel(label).build();
    }
}
