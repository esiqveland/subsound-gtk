package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

public class ArtistInfoBox extends Box {
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final ListBox list;
    private final Map<String, ArtistAlbumInfo> artistsMap;
    private final ArtistInfo artist;
    private final Box artistImage;

    public ArtistInfoBox(ArtistInfo artistInfo) {
        super(Orientation.VERTICAL, 0);
        this.artist = artistInfo;
        this.artistImage = this.artist.coverArt()
                .map(AlbumArt::new)
                .map(artwork -> (Box) artwork)
                .orElseGet(AlbumArt::placeholderImage);
        this.infoContainer = Box.builder().setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).build();
        this.infoContainer.append(this.artistImage);
        this.infoContainer.append(new Label(this.artist.name()));
        this.infoContainer.append(new Label("%d albums".formatted(this.artist.albumCount())));
        this.infoContainer.append(new Label("%d songs".formatted(this.artist.songCount())));
        this.infoContainer.append(new Label("%s playtime".formatted(formatDuration(this.artist.totalPlayTime()))));

        this.artistsMap = artistInfo.albums().stream().collect(Collectors.toMap(
                ArtistAlbumInfo::id,
                a -> a
        ));
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        this.list.onRowActivated(row -> {
            var albumInfo = this.artist.albums().get(row.getIndex());
            System.out.println("ArtistAlbum: goto " + albumInfo.name() + " (%s)".formatted(albumInfo.id()));
        });

        var stringList = StringList.builder().build();
        this.artist.albums().forEach(i -> stringList.append(i.id()));
        this.list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var albumInfo = this.artistsMap.get(id);
            return ActionRow.builder()
                    .setTitle(albumInfo.name())
                    .setSubtitle(albumInfo.songCount() + " tracks")
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

    public static String formatDuration(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();
        return  (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hours, ") +
                (minutes == 0 ? "" : minutes + " minutes, ") +
                (seconds == 0 ? "" : seconds + " seconds");
    }
}
