package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.views.components.NowPlayingOverlayIcon;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import io.github.jwharm.javagi.examples.playsound.views.components.StarredButton;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.*;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Orientation.HORIZONTAL;

public class AlbumInfoBox extends Box {
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final ListBox list;
    private final Map<String, SongInfo> songIdMap;
    //private final ArtistInfo artistInfo;
    private final AlbumInfo albumInfo;
    private final Widget artistImage;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ThumbnailCache thumbLoader;

    public AlbumInfoBox(
            ThumbnailCache thumbLoader,
            AlbumInfo albumInfo,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.onAction = onAction;
        this.artistImage = this.albumInfo.coverArt()
                .map(coverArt -> new RoundedAlbumArt(
                        coverArt,
                        this.thumbLoader,
                        300
                ))
                .map(artwork -> (Widget) artwork)
                .orElseGet(() -> RoundedAlbumArt.placeholderImage(300));
        this.infoContainer = Box.builder().setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).build();
        this.infoContainer.append(this.artistImage);
        this.infoContainer.append(infoLabel(this.albumInfo.name(), cssClasses("heading")));
        this.infoContainer.append(infoLabel(this.albumInfo.artistName(), cssClasses("dim-label", "body")));
        this.infoContainer.append(infoLabel("%d songs".formatted(this.albumInfo.songCount()), cssClasses("dim-label", "body")));
        this.infoContainer.append(infoLabel("%s playtime".formatted(formatDurationLong(this.albumInfo.totalPlayTime())), cssClasses("dim-label", "body")));

        this.songIdMap = this.albumInfo.songs().stream().collect(Collectors.toMap(
                SongInfo::id,
                a -> a
        ));

        this.list = ListBox.builder()
                .setValign(Align.START)
                // require double click to activate:
                .setActivateOnSingleClick(false)
                .setCssClasses(new String[]{"boxed-list"})
                .build();
        this.list.onRowActivated(row -> {
            var songInfo = this.albumInfo.songs().get(row.getIndex());
            System.out.println("AlbumInfoBox: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
            this.onAction.apply(new PlayerAction.PlayQueue(
                    this.albumInfo.songs(),
                    row.getIndex()
            ));
        });

        var stringList = StringList.builder().build();
        this.albumInfo.songs().forEach(i -> stringList.append(i.id()));
        this.list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var songInfo = this.songIdMap.get(id);

            var suffix = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();

            var starredButton = new StarredButton(
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
                            .setCssClasses(cssClasses("pill", "dim-label"))
                            .build()
                    );

            var fileSizeLabel = infoLabel(formatBytesSI(songInfo.size()), cssClasses("dim-label"));
            var bitRateLabel = songInfo.bitRate()
                    .map(bitRate -> infoLabel("%d kbps".formatted(bitRate), cssClasses("dim-label")));
            bitRateLabel.ifPresent(hoverBox::append);
            fileFormatLabel.ifPresent(hoverBox::append);
            hoverBox.append(fileSizeLabel);
            hoverBox.append(playButton);

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
                    this.thumbLoader,
                    songInfo.coverArt(),
                    48
            );
            var icon = new NowPlayingOverlayIcon(48, albumIcon);
            if ("166bcd70e38d8f0a202e0a907b8c0727".equals(songInfo.id())) {
                icon.setIsPlaying(true);
            }
            row.addPrefix(icon);
            row.addSuffix(suffix);
            return row;
        });

        infoContainer.append(list);
        this.scroll = ScrolledWindow.builder().setChild(infoContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }

    private int getIdx(String id, List<SongInfo> songs) {
        for (int i = 0; i < songs.size(); i++) {
            if (id.equals(songs.get(i).id())) {
                return i;
            }
        }
        throw new IllegalStateException("songs does not contain id=%s".formatted(id));
    }

    public static Label infoLabel(String label, String[] cssClazz) {
        return Label.builder().setLabel(label).setCssClasses(cssClazz).build();
    }
}
