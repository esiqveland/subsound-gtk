package io.github.jwharm.javagi.examples.playsound.components;

import org.gnome.adw.ActionRow;
import org.gnome.gio.ListModel;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;

import java.util.List;

public class ArtistListBox extends Box {
    public record ArtistInfo(
            String id,
            String name
    ) {
    }

    private final List<ArtistInfo> artists;
    private final ListBox list;

    public ArtistListBox() {
        this(List.of(
                new ArtistInfo("id1", "Coldplay"),
                new ArtistInfo("id2", "Metallica")
        ));
    }

    public ArtistListBox(List<ArtistInfo> artists) {
        super(Orientation.VERTICAL, 5);
        this.artists = artists;
        var listItems = this.artists.stream()
                .map(artist -> ActionRow.builder()
                        .setTitle(artist.name)
                        .build()
                ).toList();
        this.list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        listItems.forEach(list::append);
        ListModel.ListModelImpl.builder().build();
        this.append(list);
    }
}
