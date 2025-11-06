package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import com.github.subsound.ui.components.OverviewAlbumChild.AlbumCoverHolderSmall;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.ui.views.StarredListView;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.StateFlags;
import org.javagi.gobject.SignalConnection;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static com.github.subsound.utils.Utils.addHover2;
import static com.github.subsound.utils.Utils.cssClasses;
import static com.github.subsound.utils.Utils.formatBytesSI;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class StarredItemRow extends Box implements StarredListView.UpdateListener {
    private static final int TRACK_NUMBER_LABEL_CHARS = 4;

    private final AppManager appManager;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Consumer<AppNavigation.AppRoute> onNavigate;

    private GSongInfo gSongInfo;
    private SongInfo songInfo;

    private final Box prefixBox;
    private final Box centerBox;
    private final Box centerContent;
    private final Box subtitleBox;
    private final Box suffixBox;
    private final Overlay trackNumberOverlay;
    private final ListItemPlayingIcon trackNumberIcon;
    private final Label trackNumberLabel;
    private final TransparentNowPlayingOverlayIcon nowPlayingOverlayIcon;
    private final AlbumCoverHolderSmall albumCoverHolder;
    private final Label titleLabel;
    private final Label durationLabel;
    private final ClickLabel artistNameLabel;
    private final ClickLabel albumNameLabel;

    private final StarButton starredButton;
    private final Revealer revealer;
    private final Box hoverBox;
    private final Button fileFormatLabel;
    private final Label fileSizeLabel;
    private final Label bitRateLabel;
    private final AtomicReference<SignalConnection<?>> signal1 = new AtomicReference<>();
    private final AtomicReference<SignalConnection<?>> signal2 = new AtomicReference<>();
    private final AtomicInteger index = new AtomicInteger(0);

    public StarredItemRow(
            AppManager appManager,
            Function<PlayerAction, CompletableFuture<Void>> onAction,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(HORIZONTAL, 0);
        this.appManager = appManager;
        this.onAction = onAction;
        this.onNavigate = onNavigate;

        this.setMarginTop(4);
        this.setMarginBottom(4);
        this.setMarginStart(2);
        this.setMarginEnd(2);

        albumCoverHolder = new AlbumCoverHolderSmall(this.appManager);
        prefixBox = new Box(HORIZONTAL, 0);
        prefixBox.setHalign(START);
        prefixBox.setValign(CENTER);
        prefixBox.setVexpand(true);
        prefixBox.setHomogeneous(true);

        centerBox = new Box(HORIZONTAL, 0);
        centerBox.setHalign(START);
        centerBox.setValign(CENTER);
        centerBox.setVexpand(true);
        centerBox.setHexpand(true);
        centerBox.setMarginStart(8);
        centerBox.setMarginEnd(8);

        centerContent = new Box(VERTICAL, 4);
        centerContent.setHalign(START);
        centerContent.setValign(CENTER);
        centerContent.setVexpand(true);
        centerContent.setHexpand(true);

        subtitleBox = new Box(HORIZONTAL, 2);
        subtitleBox.setHalign(START);
        subtitleBox.setValign(CENTER);
        subtitleBox.setVexpand(true);
        subtitleBox.setHexpand(true);

        suffixBox = new Box(HORIZONTAL, 8);
        suffixBox.setHalign(Align.END);
        suffixBox.setValign(CENTER);
        suffixBox.setVexpand(true);

        hoverBox = new Box(HORIZONTAL, 8);
        hoverBox.setHalign(Align.END);
        hoverBox.setValign(CENTER);

        this.nowPlayingOverlayIcon = new TransparentNowPlayingOverlayIcon(48, this.albumCoverHolder);
        this.trackNumberLabel = infoLabel("    ", Classes.labelDim.add(Classes.labelNumeric));
        this.trackNumberLabel.setVexpand(false);
        this.trackNumberLabel.setValign(CENTER);
        this.trackNumberLabel.setMarginEnd(8);
        this.trackNumberLabel.setJustify(Justification.RIGHT);
        this.trackNumberLabel.setSingleLineMode(true);
        this.trackNumberLabel.setWidthChars(TRACK_NUMBER_LABEL_CHARS);
        this.trackNumberLabel.setMaxWidthChars(TRACK_NUMBER_LABEL_CHARS);
        this.trackNumberOverlay = Overlay.builder().setChild(this.trackNumberLabel).setOverflow(Overflow.HIDDEN).build();
        this.trackNumberIcon = new ListItemPlayingIcon(NowPlayingState.NONE, 48);
        this.trackNumberIcon.setHalign(END);
        this.trackNumberIcon.setValign(CENTER);
        this.trackNumberIcon.setVisible(false);
        this.trackNumberOverlay.addOverlay(this.trackNumberIcon);
        prefixBox.append(trackNumberOverlay);
        prefixBox.append(nowPlayingOverlayIcon);

        fileFormatLabel = Button.builder()
                .setLabel("")
                .setSensitive(false)
                .setVexpand(false)
                .setValign(CENTER)
                .setCssClasses(cssClasses("pill", "dim-label"))
                .build();

        fileSizeLabel = infoLabel("1.1 MB", cssClasses("dim-label"));
        bitRateLabel = infoLabel("%d kbps".formatted(1), cssClasses("dim-label"));

        starredButton = new StarButton(
                Optional.empty(),
                newValue -> {
                    if (this.songInfo == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    var action = newValue ? new PlayerAction.Star(this.songInfo) : new PlayerAction.Unstar(this.songInfo);
                    return this.onAction.apply(action);
                }
        );
        var starredButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(Align.CENTER).build();
        starredButtonBox.append(starredButton);

        hoverBox.append(bitRateLabel);
        hoverBox.append(fileFormatLabel);
        hoverBox.append(fileSizeLabel);
        hoverBox.append(starredButtonBox);

        revealer = Revealer.builder()
                .setChild(hoverBox)
                .setRevealChild(false)
                .setTransitionType(RevealerTransitionType.CROSSFADE)
                .build();
        suffixBox.append(revealer);

        var isHoverActive = new AtomicBoolean(false);
        addHover2(
                this,
                () -> {
                    isHoverActive.set(true);
                    revealer.setRevealChild(true);
                    this.trackNumberLabel.setVisible(false);
                    this.trackNumberIcon.setVisible(true);
                },
                () -> {
                    isHoverActive.set(false);
                    var focused = this.hasFocus();
                    //System.out.println("onLeave: focused=" + focused);
                    revealer.setRevealChild(focused);
                    if (this.playingState == NowPlayingState.NONE) {
                        this.trackNumberLabel.setVisible(!focused);
                        this.trackNumberIcon.setVisible(focused);
                    }
                }
        );
        this.onStateFlagsChanged(flags -> {
            var hasFocus = flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
            var hasHover = isHoverActive.get();
            //System.out.println("onStateFlagsChanged: " + String.join(", ", flags.stream().map(s -> s.name()).toList()) + " hasHover=" + hasHover + " hasFocus=" + hasFocus);
            //playButton.setVisible(hasFocus || hasHover);
            revealer.setRevealChild(hasFocus || hasHover);
        });


        this.titleLabel = infoLabel("", Classes.title3.add());
        this.titleLabel.setHalign(START);
        this.titleLabel.setSingleLineMode(true);

        this.durationLabel = infoLabel("", Classes.labelDim.add(Classes.caption));
        this.titleLabel.setHalign(START);
        this.titleLabel.setSingleLineMode(true);
        this.artistNameLabel = new ClickLabel("", () -> {
            var songInfo = this.songInfo;
            var artistId = this.songInfo.artistId();
            if (artistId == null) {
                System.out.println("songInfo with null artist?");
                return;
            }
            var route = new AppNavigation.AppRoute.RouteArtistInfo(this.songInfo.artistId());
            this.onNavigate.accept(route);
        });
        this.artistNameLabel.addCssClass(Classes.labelDim.className());
        this.artistNameLabel.addCssClass(Classes.caption.className());
        this.artistNameLabel.setHalign(START);
        this.artistNameLabel.setSingleLineMode(true);
        this.albumNameLabel = new ClickLabel("", () -> {
            var route = new AppNavigation.AppRoute.RouteAlbumInfo(this.songInfo.albumId());
            this.onNavigate.accept(route);
        });
        this.albumNameLabel.addCssClass(Classes.labelDim.className());
        this.albumNameLabel.addCssClass(Classes.caption.className());
        this.albumNameLabel.setHalign(START);
        this.albumNameLabel.setSingleLineMode(true);
        //this.subtitleLabel = infoLabel("", Classes.labelDim.add(Classes.caption));
        centerContent.append(titleLabel);
        centerContent.append(subtitleBox);

        subtitleBox.append(durationLabel);
        subtitleBox.append(spacerLabel("⦁"));
        subtitleBox.append(artistNameLabel);
        subtitleBox.append(spacerLabel("⦁"));
        subtitleBox.append(albumNameLabel);
        this.centerBox.append(centerContent);

        this.append(this.prefixBox);
        this.append(this.centerBox);
        this.append(this.suffixBox);
    }

    private Label spacerLabel(String text) {
        var l = infoLabel(text, Classes.labelDim.add(Classes.caption));
        l.setHalign(START);
        l.setSingleLineMode(true);
        return l;
    }

    public void setSongInfo(GSongInfo songInfo, ListItem listItem, MiniState miniState) {
        this.gSongInfo = songInfo;
        this.songInfo = songInfo.songInfo();
        var connection = this.gSongInfo.onNotify(
                GSongInfo.Signal.IS_PLAYING.getId(),
                _ -> this.update(miniState)
        );
        var old = this.signal1.getAndSet(connection);
        if (old != null) {
            old.disconnect();
        }
        // Listen for position changes
        var positionConnection = listItem.onNotify("position", param -> {
            int pos = listItem.getPosition();
            this.index.set(pos);
            int trackNumber = pos + 1;
            this.trackNumberLabel.setLabel("%d".formatted(trackNumber));
        });
        var old2 = this.signal2.getAndSet(positionConnection);
        if (old2 != null) {
            old2.disconnect();
        }

        this.index.set(listItem.getPosition());
        this.updateView();
        this.update(miniState);
    }

    public void unbind() {
        var sig1 = this.signal1.getAndSet(null);
        if (sig1 != null) {
            sig1.disconnect();
        }
        var sig2 = this.signal2.getAndSet(null);
        if (sig2 != null) {
            sig2.disconnect();
        }
    }

    private void updateView() {
        if (this.songInfo == null) {
            return;
        }
        var songInfo = this.songInfo;

        //String subtitle = "%s ⦁ %s\n%s".formatted(songInfo.artist(), songInfo.album(), durationString);
        //String subtitle = "%s ⦁ %s ⦁ %s".formatted(durationString, songInfo.artist(), songInfo.album());

        String durationString = Utils.formatDurationShort(songInfo.duration());
        this.titleLabel.setLabel(songInfo.title());
        this.durationLabel.setLabel(durationString);
        this.artistNameLabel.setLabel(songInfo.artist());
        this.albumNameLabel.setLabel(songInfo.album());
        int trackNumber = this.index.get() + 1;
        this.trackNumberLabel.setLabel("%d".formatted(trackNumber));

        var fileSuffixText = Optional.ofNullable(songInfo.suffix())
                .filter(fileExt -> !fileExt.isBlank())
                .orElse("");
        this.fileFormatLabel.setLabel(fileSuffixText);

        this.fileSizeLabel.setLabel(formatBytesSI(songInfo.size()));
        var bitRateText = songInfo.bitRate()
                .map("%d kbps"::formatted)
                .orElse("");
        this.bitRateLabel.setLabel(bitRateText);

        this.albumCoverHolder.setArtwork(songInfo.coverArt());
        this.starredButton.setStarredAt(songInfo.starred());
    }

    private NowPlayingState playingState = NowPlayingState.NONE;

    @Override
    public void update(MiniState n) {
        var next = getNextPlayingState(n);
        if (next == this.playingState) {
            return;
        }
        this.playingState = next;
        Utils.runOnMainThread(() -> {
            //this.nowPlayingOverlayIcon.setPlayingState(this.playingState);
            this.nowPlayingOverlayIcon.addCssClass("darken");
            this.trackNumberIcon.setPlayingState(this.playingState);
            switch (this.playingState) {
                case LOADING, PAUSED, PLAYING -> {
                    this.trackNumberLabel.setVisible(false);
                    this.trackNumberIcon.setVisible(true);
                    this.titleLabel.addCssClass(Classes.colorAccent.className());
                }
                case NONE -> {
                    this.trackNumberLabel.setVisible(true);
                    this.trackNumberIcon.setVisible(false);
                    this.titleLabel.removeCssClass(Classes.colorAccent.className());
                }
            }
        });
    }

    private NowPlayingState getNextPlayingState(MiniState n) {
        return n.songInfo().map(songInfo -> {
            if (songInfo.id().equals(this.songInfo.id())) {
                return n.nowPlayingState();
            } else {
                return NowPlayingState.NONE;
            }
        }).orElse(NowPlayingState.NONE);
    }
}