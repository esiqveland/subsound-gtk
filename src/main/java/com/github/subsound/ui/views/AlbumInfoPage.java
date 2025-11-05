package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.AppManager.AppState;
import com.github.subsound.app.state.AppManager.NowPlaying;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.AlbumInfo;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.Classes;
import com.github.subsound.ui.components.NowPlayingOverlayIcon;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.RoundedAlbumArt;
import com.github.subsound.ui.components.StarButton;
import com.github.subsound.utils.ImageUtils;
import com.github.subsound.utils.Utils;
import org.gnome.adw.ActionRow;
import org.gnome.gdk.Display;
import org.gnome.gdk.Gdk;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.StateFlags;
import org.gnome.gtk.StyleContext;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final AlbumInfo albumInfo;
    private final AtomicReference<AppState> prevState = new AtomicReference<>(null);

    private final Box headerBox;
    private final ScrolledWindow scroll;
    private final Box mainContainer;
    private final Box albumInfoBox;
    private final ListBox list;
    private final List<AlbumSongActionRow> rows;
    private final Widget artistImage;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private static final int COVER_SIZE = 300;
    private static final CssProvider COLOR_PROVIDER = CssProvider.builder().build();
    private static final AtomicBoolean isProviderInit = new AtomicBoolean(false);

    public static class AlbumSongActionRow extends ActionRow {
        private final AlbumInfo albumInfo;
        public final SongInfo songInfo;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        public final NowPlayingOverlayIcon icon;

        public AlbumSongActionRow(
                AlbumInfo albumInfo,
                SongInfo songInfo,
                Function<PlayerAction, CompletableFuture<Void>> onAction
        ) {
            super();
            this.albumInfo = albumInfo;
            this.songInfo = songInfo;
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

            var starredButton = new StarButton(
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

            // var playButton = SplitButton.builder()
            var playButton = Button.builder()
                    //.setLabel("Play")
                    .setIconName("media-playback-start-symbolic")
                    .setCssClasses(cssClasses("success", "circular", "raised"))
                    .setVisible(true)
                    .build();

            var fileFormatLabel = Optional.ofNullable(songInfo.suffix())
                    .filter(fileExt -> !fileExt.isBlank())
                    //.map(fileExt -> infoLabel(fileExt, cssClasses("dim-label")));
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
            var playButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(Align.CENTER).build();
            playButtonBox.append(playButton);
            hoverBox.append(playButtonBox);

            var revealer = Revealer.builder()
                    .setChild(hoverBox)
                    .setRevealChild(false)
                    .setTransitionType(RevealerTransitionType.CROSSFADE)
                    .build();

            playButton.onClicked(() -> {
                System.out.println("AlbumInfoBox.playButton: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
                var idx = getIdx(songInfo.id(), this.albumInfo.songs());
                this.onAction.apply(new PlayerAction.PlayQueue(
                        this.albumInfo.songs(),
                        idx
                ));
            });
            suffix.append(revealer);
            var starredButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(Align.CENTER).build();
            starredButtonBox.append(starredButton);
            suffix.append(starredButtonBox);

            //var trackNumberTitle = songInfo.trackNumber().map(num -> "%d ⦁ ".formatted(num)).orElse("");
            String durationString = Utils.formatDurationShortest(songInfo.duration());
            String subtitle = "" + durationString;

//            var albumIcon = RoundedAlbumArt.resolveCoverArt(
//                    this.thumbLoader,
//                    songInfo.coverArt(),
//                    48
//            );

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
                        var focused = this.hasFocus() || playButton.hasFocus();
                        //System.out.println("onLeave: focused=" + focused);
                        revealer.setRevealChild(focused);
                        this.icon.setIsHover(false);
                    }
            );
            this.onStateFlagsChanged(flags -> {
                var hasFocus = playButton.hasFocus() || flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
                var hasHover = isHoverActive.get();
                //System.out.println("onStateFlagsChanged: " + String.join(", ", flags.stream().map(s -> s.name()).toList()) + " hasHover=" + hasHover + " hasFocus=" + hasFocus);
                //playButton.setVisible(hasFocus || hasHover);
                revealer.setRevealChild(hasFocus || hasHover);
                this.icon.setIsHover(hasFocus || hasHover);
            });

            this.addPrefix(icon);
            this.addSuffix(suffix);
            this.setTitle(songInfo.title());
            this.setSubtitle(subtitle);
        }

    }

    public AlbumInfoPage(
            AppManager appManager,
            AlbumInfo albumInfo,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.albumInfo = albumInfo;
        this.onAction = onAction;
        if (isProviderInit.compareAndSet(false, true)) {
            Gtk.styleContextAddProviderForDisplay(Display.getDefault(), COLOR_PROVIDER, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
        }
        this.artistImage = this.albumInfo.coverArt()
                .map(coverArt -> new RoundedAlbumArt(
                        coverArt,
                        this.appManager,
                        COVER_SIZE
                ))
                .map(albumArt -> {
                    albumArt.addCssClass("album-main-cover");
                    return albumArt;
                })
                .map(artwork -> (Widget) artwork)
                .orElseGet(() -> RoundedAlbumArt.placeholderImage(COVER_SIZE));

        this.list = ListBox.builder()
                .setValign(Align.START)
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

        this.list.onRowActivated(row -> {
            var songInfo = this.albumInfo.songs().get(row.getIndex());
            System.out.println("AlbumInfoBox: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
            this.onAction.apply(new PlayerAction.PlayQueue(
                    this.albumInfo.songs(),
                    row.getIndex()
            ));
        });

        this.rows = this.albumInfo.songs().stream().map(songInfo -> {
            var row = new AlbumSongActionRow(this.albumInfo, songInfo, this.onAction);
            return row;
        }).toList();

        rows.forEach(this.list::append);

        this.headerBox = Box.builder().setHalign(Align.BASELINE_FILL).setSpacing(0).setValign(START).setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).build();
        this.headerBox.addCssClass("album-info-main");

        this.mainContainer = Box.builder().setHalign(BASELINE_FILL).setSpacing(8).setValign(START).setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).setMarginBottom(10).setHomogeneous(false).build();
        this.albumInfoBox = Box.builder().setHalign(CENTER).setSpacing(4).setValign(START).setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).setMarginBottom(10).build();
        this.albumInfoBox.append(infoLabel(this.albumInfo.name(), Classes.titleLarge2.add()));
        this.albumInfoBox.append(infoLabel(this.albumInfo.artistName(), Classes.titleLarge3.add()));
        this.albumInfoBox.append(infoLabel(this.albumInfo.year().map(String::valueOf).orElse(""), Classes.labelDim.add(Classes.bodyText)));
        this.albumInfoBox.append(infoLabel("%d songs, %s".formatted(this.albumInfo.songCount(), formatDurationMedium(this.albumInfo.totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));
        //this.albumInfoBox.append(infoLabel("%s playtime".formatted(formatDurationMedium(this.albumInfo.totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));
        this.headerBox.append(this.artistImage);
        this.headerBox.append(this.albumInfoBox);
        this.mainContainer.append(this.headerBox);
        var listHolder = Box.builder().setOrientation(VERTICAL).setHalign(CENTER).setValign(START).build();
        listHolder.append(list);
        this.mainContainer.append(listHolder);

        this.scroll = ScrolledWindow.builder().setChild(mainContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(this.scroll);
        this.onMap(() -> this.appManager.getThumbnailCache().loadPixbuf(this.albumInfo.coverArt().get(), COVER_SIZE).thenAccept(this::switchPallete));
    }

    private void switchPallete(ThumbnailCache.StoredPixbuf storedPixbuf) {
        StringBuilder colors = new StringBuilder();
        List<ImageUtils.ColorValue> palette = storedPixbuf.palette();
        for (int i = 0; i < palette.size(); i++) {
            ImageUtils.ColorValue colorValue = palette.get(i);
            colors.append("@define-color background_color_%d %s;\n".formatted(i, colorValue.rgba().toString()));
        }
        COLOR_PROVIDER.loadFromString(colors.toString());
    }

    public void updateAppState(AppState next) {
        var prev = prevState.get();
        prevState.set(next);
        final boolean isOutdated = needsUpdate(prev, next);
        if (!isOutdated) {
            return;
        }
        for (AlbumSongActionRow row : this.rows) {
            boolean isPlaying = next.nowPlaying().map(np -> np.song().id().equals(row.songInfo.id())).orElse(false);
            if (isPlaying) {
                row.addCssClass(Classes.colorAccent.className());
                switch (next.player().state()) {
                    case PAUSED, INIT -> row.icon.setPlayingState(NowPlayingState.PAUSED);
                    case PLAYING, BUFFERING, READY, END_OF_STREAM -> row.icon.setPlayingState(NowPlayingState.PLAYING);
                }
            } else {
                row.removeCssClass(Classes.colorAccent.className());
                row.icon.setPlayingState(NowPlayingState.NONE);
            }
        }
    }

    private boolean needsUpdate(AppState prev, AppState next) {
        if (prev == null) {
            return true;
        }
        if (prev.nowPlaying().isPresent() != next.nowPlaying().isPresent()) {
            return true;
        }
        var prevSongId = prev.nowPlaying().map(NowPlaying::song).map(SongInfo::id).orElse("");
        var nextSongId = next.nowPlaying().map(NowPlaying::song).map(SongInfo::id).orElse("");
        if (!prevSongId.equals(nextSongId)) {
            return true;
        }
        int oldPos = prev.queue().position().orElse(0);
        int newPos = next.queue().position().orElse(0);
        if (oldPos != newPos) {
            return true;
        }
        return false;
    }

    private static int getIdx(String id, List<SongInfo> songs) {
        for (int i = 0; i < songs.size(); i++) {
            if (id.equals(songs.get(i).id())) {
                return i;
            }
        }
        throw new IllegalStateException("songs does not contain id=%s".formatted(id));
    }

    public static Label infoLabel(String label, String[] cssClazz) {
        return Label.builder().setCssClasses(cssClazz).setUseMarkup(false).setEllipsize(EllipsizeMode.END).setLabel(label).build();
    }
}
