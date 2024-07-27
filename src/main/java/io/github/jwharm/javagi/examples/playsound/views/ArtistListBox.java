package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistEntry;
import org.gnome.adw.ActionRow;
import org.gnome.gtk.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ArtistListBox extends Box {
    private final ScrolledWindow scroll;
    private final ListBox list;
    private final List<ArtistEntry> artists;
    private final Map<String, ArtistEntry> artistsMap;
    private Consumer<ArtistEntry> artistSelected = (a) -> {};

    public ArtistListBox(List<ArtistEntry> artists) {
        super(Orientation.VERTICAL, 5);
        this.artists = artists;
        this.artistsMap = artists.stream().collect(Collectors.toMap(ArtistEntry::id, a -> a));
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        this.list.onRowActivated(row -> {
            var artist = this.artists.get(row.getIndex());
            System.out.println("Artists: goto " + artist.name());
            var handler = artistSelected;
            if (handler != null) {
                handler.accept(artist);
            }
        });

//        var model = ListModel.ListModelImpl.builder().build();
//        var listSTore = ListStore.builder().build();
        var stringList = StringList.builder().build();
        this.artists.forEach(i -> stringList.append(i.id()));
        this.list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var artist = this.artistsMap.get(id);
            return ActionRow.builder()
                    .setTitle(artist.name())
                    .setSubtitle(artist.albumCount() + " albums")
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();
        });

        this.scroll = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }

    public void onArtistSelected(Consumer<ArtistEntry> artistEntryConsumer) {
        this.artistSelected = artistEntryConsumer;
    }
}
