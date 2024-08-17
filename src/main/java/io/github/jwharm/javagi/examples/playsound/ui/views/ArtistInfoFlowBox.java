package io.github.jwharm.javagi.examples.playsound.ui.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.AlbumsFlowBox;
import io.github.jwharm.javagi.examples.playsound.ui.components.RoundedAlbumArt;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ArtistInfoFlowBox extends Box {
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final Map<String, ArtistAlbumInfo> artistsMap;
    private final ArtistInfo artist;
    private final Widget artistImage;
    private final ThumbnailCache thumbLoader;
    private final AlbumsFlowBox listView;

    public ArtistInfoFlowBox(
            ThumbnailCache thumbLoader,
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

        listView = new AlbumsFlowBox(thumbLoader, this.artist.albums(), (albumInfo) -> {
            System.out.println("ArtistAlbum: goto " + albumInfo.name() + " (%s)".formatted(albumInfo.id()));
            var handler = this.onAlbumSelected;
            if (handler == null) {
                return;
            }
            handler.accept(albumInfo);
        });
        infoContainer.append(listView);
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
