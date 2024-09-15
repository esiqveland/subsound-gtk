package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ListStarred;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.Classes;
import io.github.jwharm.javagi.examples.playsound.ui.components.NowPlayingOverlayIcon;
import io.github.jwharm.javagi.examples.playsound.ui.components.OverviewAlbumChild.AlbumCoverHolderSmall;
import io.github.jwharm.javagi.examples.playsound.ui.components.StarButton;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gio.ListIndexModel;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.StateFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

public class StarredListView extends Box {
    private static final Logger log = LoggerFactory.getLogger(StarredListView.class);
    private final ThumbnailCache thumbLoader;
    private final ListStarred data;
    private final ListView listView;
    private final ListIndexModel listModel;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ScrolledWindow scroll;

    public StarredListView(
            ListStarred data,
            ThumbnailCache thumbLoader,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.data = data;
        this.thumbLoader = thumbLoader;
        this.onAction = onAction;
        this.setHalign(CENTER);
        this.setValign(START);
        this.setHexpand(true);
        this.setVexpand(true);

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);

            var item = new StarredItemRow(this.thumbLoader, this.onAction);
            listitem.setChild(item);
        });
        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
            if (item == null) {
                return;
            }
            // The ListIndexModel contains ListIndexItems that contain only their index in the list.
            int index = item.getIndex();

            // Retrieve the index of the item and show the entry from the ArrayList with random strings.
            var songInfo = this.data.songs().get(index);
            if (songInfo == null) {
                return;
            }
            var child = (StarredItemRow) listitem.getChild();
            if (child == null) {
                return;
            }
            listitem.setActivatable(true);
            log.info("factory.onBind: {} {}", index, songInfo.title());
            child.setSongInfo(songInfo, index);
//            child.setLabel("%d %s - %s".formatted(index+1, songInfo.artist(), songInfo.title()));
        });
        this.listModel = ListIndexModel.newInstance(data.songs().size());
        this.listView = ListView.builder()
                //.setCssClasses(cssClasses(Classes.richlist.className()))
                //.setCssClasses(cssClasses("boxed-list"))
                .setShowSeparators(false)
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(START)
                .setValign(START)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .setModel(new SingleSelection(this.listModel))
                .setFactory(factory)
                .build();
        this.listView.onActivate(index -> {
            var songInfo = this.data.songs().get(index);
            if (songInfo == null) {
                return;
            }
            List<SongInfo> songs = this.data.songs();
            log.debug("listView.onActivate: {} {}", index, songInfo.title());
            this.onAction.apply(new PlayerAction.PlayQueue(songs, index));
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
        this.append(this.scroll);
    }

    public static class StarredItemRow extends Box {
        private static final int TRACK_NUMBER_LABEL_CHARS = 4;

        private final ThumbnailCache thumbLoader;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;

        private SongInfo songInfo;
        private int index;

        private final ActionRow row;
        private final Box prefixBox;
        private final Box suffixBox;
        private final Label trackNumberLabel;
        private final NowPlayingOverlayIcon nowPlayingOverlayIcon;
        private final AlbumCoverHolderSmall albumCoverHolder;
        private final StarButton starredButton;
        private final Revealer revealer;
        private final Box hoverBox;
        private final Button fileFormatLabel;
        private final Label fileSizeLabel;
        private final Label bitRateLabel;

        public StarredItemRow(
                ThumbnailCache thumbLoader,
                Function<PlayerAction, CompletableFuture<Void>> onAction
        ) {
            super(VERTICAL, 0);
            this.thumbLoader = thumbLoader;
            this.onAction = onAction;

            this.setMarginStart(2);
            this.setMarginEnd(2);

            albumCoverHolder = new AlbumCoverHolderSmall(this.thumbLoader);
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


            this.nowPlayingOverlayIcon = new NowPlayingOverlayIcon(48, this.albumCoverHolder);
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

        private void selectSong(SongInfo songInfo, int index) {
            if (songInfo == null) {
                return;
            }
            log.debug("selectSong: {} {}", index, songInfo.title());
            //this.onAction.apply(new PlayerAction.PlaySong(songInfo));
        }

        public void setSongInfo(SongInfo songInfo, int index) {
            this.songInfo = songInfo;
            this.index = index;
            this.updateView();
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
    }
}