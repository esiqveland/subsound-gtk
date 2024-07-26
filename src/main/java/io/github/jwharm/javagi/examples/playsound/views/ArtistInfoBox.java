package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ThumbLoader;
import io.github.jwharm.javagi.examples.playsound.views.components.AlbumArt;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ArtistInfoBox extends Box {
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final ListBox list;
    private final Map<String, ArtistAlbumInfo> artistsMap;
    private final ArtistInfo artist;
    private final Widget artistImage;
    private final ThumbLoader thumbLoader;

    public ArtistInfoBox(
            ThumbLoader thumbLoader,
            ArtistInfo artistInfo,
            Consumer<ArtistAlbumInfo> onAlbumSelected
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.artist = artistInfo;
        this.onAlbumSelected = onAlbumSelected;
        this.artistImage = RoundedAlbumArt.resolveCoverArt(this.thumbLoader, this.artist.coverArt(), 300);
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
            var handler = this.onAlbumSelected;
            if (handler == null) {
                return;
            }
            handler.accept(albumInfo);
        });

        var stringList = StringList.builder().build();
        this.artist.albums().forEach(i -> stringList.append(i.id()));
        this.list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var albumInfo = this.artistsMap.get(id);

            String yearLine = albumInfo.year().map(year -> "%d ⦁ ".formatted(year)).orElse("");
            String genreLine = albumInfo.genre().map(genre -> "%s ⦁ ".formatted(genre)).orElse("");
            String subtitle = yearLine + genreLine + albumInfo.songCount() + " tracks";
            var row = ActionRow.builder()
                    .setTitle(albumInfo.name())
                    .setSubtitle(subtitle)
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();

            row.addPrefix(RoundedAlbumArt.resolveCoverArt(thumbLoader, albumInfo.coverArt(), 48));
            return row;
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
        return (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hours, ") +
                (minutes == 0 ? "" : minutes + " minutes, ") +
                (seconds == 0 ? "" : seconds + " seconds");
    }
}
