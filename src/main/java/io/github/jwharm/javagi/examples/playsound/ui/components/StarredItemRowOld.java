package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.views.StarredListView;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.StateFlags;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static io.github.jwharm.javagi.examples.playsound.ui.views.AlbumInfoBox.infoLabel;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.addHover2;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatBytesSI;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class StarredItemRowOld extends Box implements StarredListView.UpdateListener {
    private static final int TRACK_NUMBER_LABEL_CHARS = 4;

    private final ThumbnailCache thumbLoader;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;

    private ServerClient.SongInfo songInfo;
    private int index;

    private final ActionRow row;
    private final Box prefixBox;
    private final Box suffixBox;
    private final Label trackNumberLabel;
    private final TransparentNowPlayingOverlayIcon nowPlayingOverlayIcon;
    private final OverviewAlbumChild.AlbumCoverHolderSmall albumCoverHolder;
    private final StarButton starredButton;
    private final Revealer revealer;
    private final Box hoverBox;
    private final Button fileFormatLabel;
    private final Label fileSizeLabel;
    private final Label bitRateLabel;

    public StarredItemRowOld(
            ThumbnailCache thumbLoader,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.onAction = onAction;

        this.setMarginStart(2);
        this.setMarginEnd(2);

        albumCoverHolder = new OverviewAlbumChild.AlbumCoverHolderSmall(this.thumbLoader);
        prefixBox = Box.builder()
                .setOrientation(HORIZONTAL)
                .setHalign(START)
                .setValign(CENTER)
                .setVexpand(true)
                .setSpacing(0)
                .build();

        suffixBox = Box.builder()
                .setOrientation(HORIZONTAL)
                .setHalign(Align.END)
                .setValign(CENTER)
                .setVexpand(true)
                .setSpacing(8)
                .build();

        hoverBox = Box.builder()
                .setOrientation(HORIZONTAL)
                .setHalign(Align.END)
                .setValign(CENTER)
                .setSpacing(8)
                .build();

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

        this.row = ActionRow.builder()
                .setTitle("")
                .setSubtitle("")
                .setUseMarkup(false)
                .setActivatable(false)
                .setFocusable(true)
                .setFocusOnClick(true)
                .build();

        var isHoverActive = new AtomicBoolean(false);
        addHover2(
                row,
                () -> {
                    isHoverActive.set(true);
                    revealer.setRevealChild(true);
                },
                () -> {
                    isHoverActive.set(false);
                    var focused = this.hasFocus();
                    //System.out.println("onLeave: focused=" + focused);
                    revealer.setRevealChild(focused);
                }
        );
        this.onStateFlagsChanged(flags -> {
            var hasFocus = flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
            var hasHover = isHoverActive.get();
            //System.out.println("onStateFlagsChanged: " + String.join(", ", flags.stream().map(s -> s.name()).toList()) + " hasHover=" + hasHover + " hasFocus=" + hasFocus);
            //playButton.setVisible(hasFocus || hasHover);
            revealer.setRevealChild(hasFocus || hasHover);
        });


        this.nowPlayingOverlayIcon = new TransparentNowPlayingOverlayIcon(48, this.albumCoverHolder);
        this.trackNumberLabel = infoLabel("    ", Classes.labelDim.add(Classes.labelNumeric));
        this.trackNumberLabel.setVexpand(false);
        this.trackNumberLabel.setValign(CENTER);
        this.trackNumberLabel.setMarginEnd(8);
        this.trackNumberLabel.setJustify(Justification.RIGHT);
        this.trackNumberLabel.setSingleLineMode(true);
        this.trackNumberLabel.setWidthChars(TRACK_NUMBER_LABEL_CHARS);
        this.trackNumberLabel.setMaxWidthChars(TRACK_NUMBER_LABEL_CHARS);
        prefixBox.append(trackNumberLabel);
        prefixBox.append(nowPlayingOverlayIcon);

        this.row.addPrefix(prefixBox);
        this.row.addSuffix(suffixBox);
        this.append(this.row);
    }

    public void setSongInfo(ServerClient.SongInfo songInfo, int index, MiniState miniState) {
        this.songInfo = songInfo;
        this.index = index;
        this.updateView();
        this.update(miniState);
    }

    private void updateView() {
        if (this.songInfo == null) {
            return;
        }
        var songInfo = this.songInfo;

        String durationString = Utils.formatDurationShort(songInfo.duration());
        //String subtitle = "%s ⦁ %s\n%s".formatted(songInfo.artist(), songInfo.album(), durationString);
        String subtitle = "%s ⦁ %s ⦁ %s".formatted(durationString, songInfo.artist(), songInfo.album());

        this.row.setTitle(songInfo.title());
        this.row.setSubtitle(subtitle);
        this.row.setSubtitleLines(1);
        this.row.setFocusable(true);
        int trackNumber = this.index + 1;
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

    private NowPlayingOverlayIcon.NowPlayingState playingState = NowPlayingOverlayIcon.NowPlayingState.NONE;

    @Override
    public void update(MiniState n) {
        var next = getNextPlayingState(n);
        if (next == this.playingState) {
            return;
        }
        this.playingState = next;
        Utils.runOnMainThread(() -> {
            this.nowPlayingOverlayIcon.setPlayingState(this.playingState);
            switch (this.playingState) {
                case PAUSED -> {
                }
                case PLAYING -> this.row.addCssClass(Classes.colorAccent.className());
                case NONE -> this.row.removeCssClass(Classes.colorAccent.className());
            }
        });
    }

    private NowPlayingOverlayIcon.NowPlayingState getNextPlayingState(MiniState n) {
        return n.songInfo().map(songInfo -> {
            if (songInfo.id().equals(this.songInfo.id())) {
                return n.nowPlayingState();
            } else {
                return NowPlayingOverlayIcon.NowPlayingState.NONE;
            }
        }).orElse(NowPlayingOverlayIcon.NowPlayingState.NONE);
    }
}
