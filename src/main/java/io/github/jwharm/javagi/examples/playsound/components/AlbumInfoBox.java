package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.AlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
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
    private final Box artistImage;
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
                .map(coverArt -> new AlbumArt(
                        coverArt,
                        thumbLoader
                ))
                .map(artwork -> (Box) artwork)
                .orElseGet(AlbumArt::placeholderImage);
        this.infoContainer = Box.builder().setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).build();
        this.infoContainer.append(this.artistImage);
        this.infoContainer.append(new Label(this.albumInfo.name()));
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
            var subtitle = songInfo.trackNumber().map(num -> "" + num).orElse("");

//            return ListBoxRow.builder()
//                    .setActivatable(true)
//                    //.setSelectable(true)
//                    .setChild()
//                    .build();

            return ActionRow.builder()
                    .setTitle(songInfo.title())
                    .setSubtitle(subtitle)
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();
        });

        infoContainer.append(list);
        this.scroll = ScrolledWindow.builder().setChild(infoContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }
}
