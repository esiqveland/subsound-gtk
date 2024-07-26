package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbLoader;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.formatDurationLong;

public class AlbumInfoBox extends Box {
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final ListBox list;
    private final Map<String, SongInfo> songIdMap;
    //private final ArtistInfo artistInfo;
    private final AlbumInfo albumInfo;
    private final Widget artistImage;
    private final Consumer<SongInfo> onSongSelected;
    private final ThumbLoader thumbLoader;

    public AlbumInfoBox(
            ThumbLoader thumbLoader,
            AlbumInfo albumInfo,
            Consumer<SongInfo> onSongSelected
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.albumInfo = albumInfo;
        this.onSongSelected = onSongSelected;
        this.artistImage = this.albumInfo.coverArt()
                .map(coverArt -> new RoundedAlbumArt(
                        coverArt,
                        thumbLoader,
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
            this.onSongSelected.accept(songInfo);
        });

        var stringList = StringList.builder().build();
        this.albumInfo.songs().forEach(i -> stringList.append(i.id()));
        this.list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var songInfo = this.songIdMap.get(id);
            var isStarred = songInfo.starred().map(s -> true).orElse(false);
            var starredString = isStarred ? "★" : "☆";

            var suffix = Box.builder()
                    .setOrientation(Orientation.HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(Align.CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();
            var starredBtn = Label.builder().setLabel(starredString).setCssClasses(new String[]{"starred"}).build();
//            var playButton = SplitButton.builder()
            var playButton = Button.builder()
                    //.setLabel("Play")
                    .setIconName("media-playback-start-symbolic")
                    .setCssClasses(cssClasses("success", "circular", "raised"))
                    .setVisible(true)
                    .build();

            var revealer = Revealer.builder()
                    .setChild(playButton)
                    .setRevealChild(false)
                    .setTransitionType(RevealerTransitionType.CROSSFADE)
                    .build();

            playButton.onClicked(() -> {
                System.out.println("AlbumInfoBox.playButton: play " + songInfo.title() + " (%s)".formatted(songInfo.id()));
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
                        //playButton.setVisible(true);
                        revealer.setRevealChild(true);
                    },
                    () -> {
                        isHoverActive.set(false);
                        var focused = row.hasFocus();
                        //System.out.println("onLeave: focused=" + focused);
                        //playButton.setVisible(focused);
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
                    albumInfo.coverArt(),
                    48
            ));
            row.addSuffix(suffix);
            return row;
        });

        infoContainer.append(list);
        this.scroll = ScrolledWindow.builder().setChild(infoContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }

    private ActionRow addHover(ActionRow row, Runnable onEnter, Runnable onLeave) {
        var ec = EventControllerMotion.builder().setPropagationPhase(PropagationPhase.CAPTURE).setPropagationLimit(PropagationLimit.NONE).build();
        ec.onEnter((x, y) -> {
            onEnter.run();
        });
        ec.onLeave(() -> {
            onLeave.run();
        });
        row.addController(ec);
        return row;
    }

    private Label infoLabel(String label, String[] cssClazz) {
        return Label.builder().setLabel(label).setCssClasses(cssClazz).build();
    }
}
