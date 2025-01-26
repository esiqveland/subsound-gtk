package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.components.NowPlayingOverlayIcon;
import com.github.subsound.ui.components.RoundedAlbumArt;
import com.github.subsound.ui.components.StarButton;
import com.github.subsound.utils.Utils;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.StateFlags;
import org.gnome.gtk.StringList;
import org.gnome.gtk.StringObject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static com.github.subsound.utils.Utils.addHover;
import static com.github.subsound.utils.Utils.cssClasses;
import static com.github.subsound.utils.Utils.formatBytesSI;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Orientation.HORIZONTAL;

public class SongList extends ListBox {
    private final AppManager appManager;
    private final List<SongInfo> songs;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Consumer<SongInfo> onSongSelected;
    private final Map<String, SongInfo> songIdMap;

    public SongList(
            AppManager appManager,
            List<SongInfo> songs,
            Function<PlayerAction, CompletableFuture<Void>> onAction,
            Consumer<SongInfo> onSongSelected
    ) {
        super();
        this.appManager = appManager;
        this.songs = songs;
        this.onAction = onAction;
        this.onSongSelected = onSongSelected;
        this.songIdMap = this.songs.stream().collect(Collectors.toMap(
                SongInfo::id,
                a -> a
        ));
        this.setHexpand(true);
        this.setVexpand(true);
        this.setValign(Align.START);
        this.setHalign(Align.FILL);
        // require double click to activate:
        this.setActivateOnSingleClick(false);
        this.setCssClasses(new String[]{"boxed-list"});
        this.onRowActivated(row -> {
            var songInfo = this.songs.get(row.getIndex());
            System.out.println("SongList: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
            this.onSongSelected.accept(songInfo);
        });

        var stringList = StringList.builder().build();

        this.songs.forEach(i -> stringList.append(i.id()));

        this.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var songInfo = this.songIdMap.get(id);

            var starredButton = new StarButton(
                    songInfo.starred(),
                    newValue -> {
                        var action = newValue ? new PlayerAction.Star(songInfo) : new PlayerAction.Unstar(songInfo);
                        return this.onAction.apply(action);
                    }
            );

            var suffix = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();

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
                            .setCssClasses(cssClasses("pill", "dim-label"))
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
                System.out.println("SongList.playButton: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
                this.onSongSelected.accept(songInfo);
            });
            suffix.append(revealer);
            suffix.append(starredButton);

            var trackNumberTitle = songInfo.trackNumber().map(num -> "%d ⦁ ".formatted(num)).orElse("");
            String durationString = Utils.formatDurationShort(songInfo.duration());
            String subtitle = trackNumberTitle + durationString;

            var row = ActionRow.builder()
                    .setTitle(songInfo.title())
                    .setSubtitle(subtitle)
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .setFocusable(true)
                    .setFocusOnClick(true)
                    .build();

            var isHoverActive = new AtomicBoolean(false);
            addHover(
                    row,
                    () -> {
                        isHoverActive.set(true);
                        revealer.setRevealChild(true);
                    },
                    () -> {
                        isHoverActive.set(false);
                        var focused = row.hasFocus() || playButton.hasFocus();
                        //System.out.println("onLeave: focused=" + focused);
                        revealer.setRevealChild(focused);
                    }
            );
            row.onStateFlagsChanged(flags -> {
                var hasFocus = playButton.hasFocus() || flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
                var hasHover = isHoverActive.get();
                //System.out.println("onStateFlagsChanged: " + String.join(", ", flags.stream().map(s -> s.name()).toList()) + " hasHover=" + hasHover + " hasFocus=" + hasFocus);
                //playButton.setVisible(hasFocus || hasHover);
                revealer.setRevealChild(hasFocus || hasHover);
            });

            var albumIcon = RoundedAlbumArt.resolveCoverArt(
                    this.appManager,
                    songInfo.coverArt(),
                    48,
                    false
            );
            var icon = new NowPlayingOverlayIcon(48, albumIcon);
            row.addPrefix(icon);
            row.addSuffix(suffix);
            return row;
        });

    }
}
