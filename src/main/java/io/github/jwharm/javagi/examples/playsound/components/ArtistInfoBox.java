package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistAlbumInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistEntry;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistInfo;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ArtistInfoBox extends Box {
    private final ScrolledWindow scroll;
    private final Box infoContainer;
    private final ListBox list;
    private final Map<String, ArtistAlbumInfo> artistsMap;
    private final ArtistInfo artist;

    public ArtistInfoBox(ArtistInfo artistInfo) {
        super(Orientation.VERTICAL, 5);
        this.artist = artistInfo;
        this.infoContainer = Box.builder().setHexpand(true).setVexpand(true).build();
        this.artistImage = new AlbumArt();

        this.artistsMap = artistInfo.albums().stream().collect(Collectors.toMap(
                ArtistAlbumInfo::id,
                a -> a
        ));
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        this.list.onRowActivated(row -> {
            var albumInfo = this.artist.albums().get(row.getIndex());
            System.out.println("ArtistAlbum: goto " + albumInfo.name());
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

        this.scroll = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }
}
