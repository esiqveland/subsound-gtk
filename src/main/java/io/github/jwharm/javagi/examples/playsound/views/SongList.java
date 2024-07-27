package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatBytesSI;
import static io.github.jwharm.javagi.examples.playsound.views.AlbumInfoBox.addHover;
import static io.github.jwharm.javagi.examples.playsound.views.AlbumInfoBox.infoLabel;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Orientation.HORIZONTAL;

public class SongList extends ListBox {

    private final ThumbnailCache thumbLoader;
    private final List<SongInfo> songs;
    private final Consumer<SongInfo> onSongSelected;
    private final Map<String, SongInfo> songIdMap;

    public SongList(ThumbnailCache thumbLoader, List<SongInfo> songs, Consumer<SongInfo> onSongSelected) {
        super();
        this.thumbLoader = thumbLoader;
        this.songs = songs;
        this.onSongSelected = onSongSelected;
        this.songIdMap = this.songs.stream().collect(Collectors.toMap(
                SongInfo::id,
                a -> a
        ));
        this.setHexpand(true);
        //this.setVexpand(true);
        this.setValign(Align.START);
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
            var isStarred = songInfo.starred().map(s -> true).orElse(false);
            var starredString = isStarred ? "★" : "☆";

            var suffix = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();
            var starredBtn = Label.builder().setLabel(starredString).setCssClasses(new String[]{"starred"}).build();


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
                System.out.println("SongList.playButton: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
                this.onSongSelected.accept(songInfo);
            });
            suffix.append(revealer);
            suffix.append(starredBtn);

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

            row.addPrefix(RoundedAlbumArt.resolveCoverArt(
                    thumbLoader,
                    songInfo.coverArt(),
                    48
            ));
            row.addSuffix(suffix);
            return row;
        });

    }
}
