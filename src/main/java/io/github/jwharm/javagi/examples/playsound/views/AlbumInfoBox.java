package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.examples.playsound.views.components.AlbumArt;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
        this.infoContainer.append(new Label(this.albumInfo.name()));
        this.infoContainer.append(new Label(this.albumInfo.artistName()));
        this.infoContainer.append(new Label("%d songs".formatted(this.albumInfo.songCount())));
        this.infoContainer.append(new Label("%s playtime".formatted(formatDurationLong(this.albumInfo.totalPlayTime()))));

        this.songIdMap = this.albumInfo.songs().stream().collect(Collectors.toMap(
                SongInfo::id,
                a -> a
        ));
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
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
            var suffix = Label.builder().setLabel(starredString).setCssClasses(new String[]{"starred"}).build();

            var trackNumberTitle = songInfo.trackNumber().map(num -> "%d ⦁ ".formatted(num)).orElse("");
            String durationString = Utils.formatDurationShort(songInfo.duration());
            String subtitle = trackNumberTitle + durationString;

            var row = ActionRow.builder()
                    .setTitle(songInfo.title())
                    .setSubtitle(subtitle)
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();
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
}
