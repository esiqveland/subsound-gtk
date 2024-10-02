package com.github.subsound.ui.views;

import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AlbumsFlowBox;
import com.github.subsound.ui.components.RoundedAlbumArt;
import org.gnome.gtk.*;
import org.gnome.pango.WrapMode;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.subsound.utils.Utils.borderBox;
import static com.github.subsound.utils.Utils.cssClasses;

public class ArtistInfoFlowBox extends Box {
    public static final int BIG_SPACING = 48;
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final Map<String, ArtistAlbumInfo> artistsMap;
    private final ArtistInfo artist;
    private final Widget artistImage;
    private final ThumbnailCache thumbLoader;
    private final AlbumsFlowBox listView;

    private final Box viewBox;
    private final Box artistInfoBox;
    private final Box biographyBox;
    private final Box biographyBoxBox;

    public ArtistInfoFlowBox(
            ThumbnailCache thumbLoader,
            ArtistInfo artistInfo,
            Consumer<ArtistAlbumInfo> onAlbumSelected
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.artist = artistInfo;
        this.onAlbumSelected = onAlbumSelected;
        this.viewBox = Box.builder().setSpacing(BIG_SPACING).setOrientation(Orientation.VERTICAL).setHexpand(true).setVexpand(true).build();
        this.artistImage = RoundedAlbumArt.resolveCoverArt(this.thumbLoader, this.artist.coverArt(), 300);
        this.infoContainer = Box.builder().setSpacing(BIG_SPACING).setOrientation(Orientation.HORIZONTAL).setHexpand(true).setVexpand(false).setCssClasses(cssClasses("card")).build();
        this.artistInfoBox = Box.builder().setSpacing(10).setMarginEnd(BIG_SPACING).setOrientation(Orientation.VERTICAL).setHexpand(true).setHalign(Align.START).setValign(Align.CENTER).build();
        this.infoContainer.append(this.artistImage);
        this.infoContainer.append(this.artistInfoBox);
        this.artistInfoBox.append(Label.builder().setLabel(this.artist.name()).setHalign(Align.START).setCssClasses(cssClasses("title-1")).build());
        this.artistInfoBox.append(Label.builder().setLabel("%d albums".formatted(this.artist.albumCount())).setHalign(Align.START).setCssClasses(cssClasses("title-3")).build());
        this.artistInfoBox.append(Label.builder().setLabel("%d songs".formatted(this.artist.songCount())).setHalign(Align.START).setCssClasses(cssClasses("dim-label")).build());
        this.artistInfoBox.append(Label.builder().setLabel("%s playtime".formatted(formatDuration(this.artist.totalPlayTime()))).setHalign(Align.START).setCssClasses(cssClasses("dim-label")).build());

        this.biographyBoxBox = borderBox(Orientation.VERTICAL, BIG_SPACING).setSpacing(BIG_SPACING/4).build();
        this.biographyBoxBox.append(Label.builder().setLabel("About").setHalign(Align.START).setCssClasses(cssClasses("title-3")).build());
        this.biographyBoxBox.append(Label.builder().setLabel(this.artist.biography().cleaned()).setWrap(true).setWrapMode(WrapMode.WORD).setNaturalWrapMode(NaturalWrapMode.WORD).setUseMarkup(false).setHalign(Align.START).setCssClasses(cssClasses("dim-label")).build());
        this.biographyBoxBox.append(Label.builder().setLabel(this.artist.biography().link()).setWrap(true).setWrapMode(WrapMode.WORD).setNaturalWrapMode(NaturalWrapMode.WORD).setUseMarkup(true).setHalign(Align.START).build());
        this.biographyBox = Box.builder().setOrientation(Orientation.VERTICAL).setHexpand(true).setCssClasses(cssClasses("card")).build();
        this.biographyBox.append(biographyBoxBox);

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
        var box = Box.builder().setOrientation(Orientation.VERTICAL).build();
        box.append(listView);

        this.viewBox.append(infoContainer);
        this.viewBox.append(this.biographyBox);
        this.viewBox.append(box);
        var mainContainer = borderBox(Orientation.VERTICAL, BIG_SPACING).build();
        mainContainer.append(viewBox);
        this.scroll = ScrolledWindow.builder().setChild(mainContainer).setHexpand(true).setVexpand(true).build();
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
