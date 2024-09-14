package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ListStarred;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.NowPlayingOverlayIcon;
import io.github.jwharm.javagi.examples.playsound.ui.components.OverviewAlbumChild.AlbumCoverHolderSmall;
import io.github.jwharm.javagi.examples.playsound.ui.components.StarButton;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gio.ListIndexModel;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
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

public class StarredListView extends Box {
    private final ThumbnailCache thumbLoader;
    private final ListStarred data;
    private final ListView listView;
    private final ListIndexModel listModel;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;

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

            var item = new StarredItemActionRow(this.thumbLoader, this.onAction);
            listitem.setChild(item);
        });
        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
            StarredItemActionRow child = (StarredItemActionRow) listitem.getChild();
            if (child == null || item == null) {
                return;
            }
            listitem.setActivatable(true);

            // The ListIndexModel contains ListIndexItems that contain only their index in the list.
            int index = item.getIndex();

            // Retrieve the index of the item and show the entry from the ArrayList with random strings.
            var songInfo = this.data.songs().get(index);
            child.setSongInfo(songInfo);
        });
        this.listModel = ListIndexModel.newInstance(data.songs().size());
        this.listView = ListView.builder()
                .setModel(new SingleSelection(this.listModel))
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(START)
                .setValign(START)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .setFactory(factory)
                .build();
        this.listView.onActivate(index -> {
            var songInfo = this.data.songs().get(index);
            if (songInfo == null) {
                return;
            }
            this.onAction.apply(new PlayerAction.PlaySong(songInfo));
        });
        this.append(this.listView);
    }

    public static class StarredItemActionRow extends ActionRow {
        private final ThumbnailCache thumbLoader;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;

        private SongInfo songInfo;

        private final NowPlayingOverlayIcon nowPlayingOverlayIcon;
        private final AlbumCoverHolderSmall albumCoverHolder;
        private final StarButton starredButton;
        private final Revealer revealer;
        private final Box hoverBox;
        private final Button playButton;
        private final Button fileFormatLabel;
        private final Label fileSizeLabel;
        private final Label bitRateLabel;

        public StarredItemActionRow(
                ThumbnailCache thumbLoader,
                Function<PlayerAction, CompletableFuture<Void>> onAction
        ) {
            this.thumbLoader = thumbLoader;
            this.onAction = onAction;

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

            albumCoverHolder = new AlbumCoverHolderSmall(this.thumbLoader);
            var suffix = Box.builder()
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

            playButton = Button.builder()
                    //.setLabel("Play")
                    .setIconName("media-playback-start-symbolic")
                    .setCssClasses(cssClasses("success", "circular", "raised"))
                    .setVisible(true)
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

            hoverBox.append(bitRateLabel);
            hoverBox.append(fileFormatLabel);
            hoverBox.append(fileSizeLabel);

            var playButtonBox = Box.builder().setMarginStart(6).setMarginEnd(6).setVexpand(true).setValign(Align.CENTER).build();
            playButtonBox.append(playButton);
            hoverBox.append(playButtonBox);

            revealer = Revealer.builder()
                    .setChild(hoverBox)
                    .setRevealChild(false)
                    .setTransitionType(RevealerTransitionType.CROSSFADE)
                    .build();

            playButton.onClicked(() -> {
                System.out.println("SongList.playButton: play " + this.songInfo.title() + " (%s)".formatted(this.songInfo.id()));
                this.selectSong(this.songInfo);
            });
            suffix.append(revealer);
            suffix.append(starredButton);

            this.setTitle("");
            this.setSubtitle("");
            this.setUseMarkup(false);
            this.setActivatable(true);
            this.setFocusable(true);
            this.setFocusOnClick(true);

            var isHoverActive = new AtomicBoolean(false);
            addHover2(
                    this,
                    () -> {
                        isHoverActive.set(true);
                        revealer.setRevealChild(true);
                    },
                    () -> {
                        isHoverActive.set(false);
                        var focused = this.hasFocus() || playButton.hasFocus();
                        //System.out.println("onLeave: focused=" + focused);
                        revealer.setRevealChild(focused);
                    }
            );
            this.onStateFlagsChanged(flags -> {
                var hasFocus = playButton.hasFocus() || flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
                var hasHover = isHoverActive.get();
                //System.out.println("onStateFlagsChanged: " + String.join(", ", flags.stream().map(s -> s.name()).toList()) + " hasHover=" + hasHover + " hasFocus=" + hasFocus);
                //playButton.setVisible(hasFocus || hasHover);
                revealer.setRevealChild(hasFocus || hasHover);
            });


            nowPlayingOverlayIcon = new NowPlayingOverlayIcon(48, this.albumCoverHolder);
            this.addPrefix(nowPlayingOverlayIcon);
            this.addSuffix(suffix);
        }

        private void selectSong(SongInfo songInfo) {
            if (songInfo == null) {
                return;
            }
            this.onAction.apply(new PlayerAction.PlaySong(songInfo));
        }

        public void setSongInfo(SongInfo songInfo) {
            this.songInfo = songInfo;
            this.updateView();
        }

        private void updateView() {
            if (this.songInfo == null) {
                return;
            }
            var songInfo = this.songInfo;

            String durationString = Utils.formatDurationShort(songInfo.duration());
            String subtitle = "%s ⦁ %s".formatted(songInfo.artist(), durationString);

            this.setTitle(songInfo.title());
            this.setSubtitle(subtitle);

            Optional.ofNullable(songInfo.suffix())
                    .filter(fileExt -> !fileExt.isBlank())
                    .ifPresent(this.fileFormatLabel::setLabel);

            fileSizeLabel.setLabel(formatBytesSI(songInfo.size()));
            songInfo.bitRate()
                    .map("%d kbps"::formatted)
                    .ifPresent(this.bitRateLabel::setLabel);
            this.albumCoverHolder.setArtwork(songInfo.coverArt());
            this.starredButton.setStarredAt(songInfo.starred());
        }
    }
}