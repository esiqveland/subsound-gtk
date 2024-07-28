package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ArtistEntry;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.views.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.gtk.*;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ArtistsListBox extends Box {
    private final ThumbnailCache thumbLoader;
    private final List<ArtistEntry> artists;
    private final Map<String, ArtistEntry> artistsMap;
    private Consumer<ArtistEntry> artistSelected;
    private final NavigationSplitView view;
    private final NavigationPage page1;
    //private final NavigationPage page2;

    public ArtistsListBox(ThumbnailCache thumbLoader, List<ArtistEntry> artists, Consumer<ArtistEntry> artistSelected) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.artists = artists;
        this.artistsMap = artists.stream().collect(Collectors.toMap(ArtistEntry::id, a -> a));
        this.artistSelected = artistSelected;
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.view = NavigationSplitView.builder().setValign(Align.FILL).setHalign(Align.FILL).setHexpand(true).setVexpand(true).build();

        var list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        list.onRowActivated(row -> {
            var artist = this.artists.get(row.getIndex());
            System.out.println("Artists: goto " + artist.name());
            var handler = this.artistSelected;
            if (handler != null) {
                handler.accept(artist);
            }
        });

//        var model = ListModel.ListModelImpl.builder().build();
//        var listSTore = ListStore.builder().build();
        var stringList = StringList.builder().build();
        this.artists.forEach(i -> stringList.append(i.id()));
        list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var artist = this.artistsMap.get(id);
            var row = ActionRow.builder()
                    .setTitle(artist.name())
                    .setSubtitle(artist.albumCount() + " albums")
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();
            row.addPrefix(RoundedAlbumArt.resolveCoverArt(
                    thumbLoader,
                    artist.coverArt(),
                    48
            ));
            return row;
        });
        var artistView = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.page1 = NavigationPage.builder().setTag("page-1").setChild(artistView).setTitle("Artists").build();
        this.view.setSidebar(this.page1);
        this.view.setShowContent(false);
        //this.artistLoader = new ArtistInfoLoader();
        //this.page2 = NavigationPage.builder().setTag("page-2").setChild().setTitle("ArtistView").build();
        //this.view.setContent(this.page2);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(view);
    }

    public void onArtistSelected(Consumer<ArtistEntry> artistEntryConsumer) {
        this.artistSelected = artistEntryConsumer;
    }
}
