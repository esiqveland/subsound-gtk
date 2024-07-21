package io.github.jwharm.javagi.examples.playsound.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistEntry;
import org.gnome.adw.ActionRow;
import org.gnome.gio.ListModel;
import org.gnome.gtk.*;

import java.util.List;

public class ArtistListBox extends Box {
    private final ScrolledWindow scroll;
    private final ListBox list;
    private final List<ArtistEntry> artists;

    public ArtistListBox() {
        this(List.of(
                new ArtistEntry("id1", "Coldplay", 1),
                new ArtistEntry("id2", "Metallica", 5)
        ));
    }

    public ArtistListBox(List<ArtistEntry> artists) {
        super(Orientation.VERTICAL, 5);
        this.artists = artists;
        var listItems = this.artists.stream()
                .map(artist -> ActionRow.builder()
                        .setTitle(artist.name())
                        .setSubtitle(artist.albumCount() + " albums")
                        .setUseMarkup(false)
                        .setActivatable(true)
                        .build()
                ).toList();
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        listItems.forEach(item -> list.append(item));
        ListModel.ListModelImpl.builder().build();

        this.list.onRowActivated(row -> {
            var artist = this.artists.get(row.getIndex());
            System.out.println("Artists: goto " + artist.name());
        });
        this.scroll = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
    }
}
