package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.ArtistAlbumInfo;
import com.github.subsound.integration.ServerClient.ArtistEntry;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.RoundedAlbumArt;
import org.gnome.adw.ActionRow;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.StringList;
import org.gnome.gtk.StringObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.github.subsound.utils.Utils.cssClasses;

public class ArtistsListBox extends Box {
    private final ThumbnailCache thumbLoader;
    private final AppManager client;
    private final List<ArtistEntry> artists;
    private final Map<String, ArtistEntry> artistsMap;
    private Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final NavigationSplitView view;
    private final NavigationPage initialPage;
    private final NavigationPage page1;
    private final NavigationPage contentPage;
    private final ArtistInfoLoader artistInfoLoader;

    public ArtistsListBox(ThumbnailCache thumbLoader, AppManager client, List<ArtistEntry> artists, Consumer<ArtistAlbumInfo> onAlbumSelected) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.client = client;
        this.artists = artists;
        this.artistsMap = artists.stream().collect(Collectors.toMap(ArtistEntry::id, a -> a));
        this.onAlbumSelected = onAlbumSelected;
        this.artistInfoLoader = new ArtistInfoLoader(this.thumbLoader, this.client, albumInfo -> this.onAlbumSelected.accept(albumInfo));
        this.contentPage = NavigationPage.builder().setTag("page-2").setChild(this.artistInfoLoader).setTitle("ArtistView").build();

        var b = Box.builder().setValign(Align.CENTER).setHalign(Align.CENTER).build();
        b.append(Label.builder().setLabel("Select an artist to view").setCssClasses(cssClasses("title-1")).build());
        var statusPage = StatusPage.builder().setChild(b).build();
        this.initialPage = NavigationPage.builder().setTag("page-2-initial").setChild(statusPage).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.view = NavigationSplitView.builder().setValign(Align.FILL).setHalign(Align.FILL).setHexpand(true).setVexpand(true).build();

        var list = ListBox.builder().setValign(Align.START).setCssClasses(new String[]{"boxed-list"}).build();
        list.onRowActivated(row -> {
            var artist = this.artists.get(row.getIndex());
            System.out.println("Artists: goto " + artist.name());
            this.contentPage.setTitle(artist.name());
            this.setSelectedArtist(artist.id());
        });

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
                    this.client,
                    artist.coverArt(),
                    48,
                    false
            ));
            return row;
        });
        var artistView = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.page1 = NavigationPage.builder().setTag("page-1").setChild(artistView).setTitle("Artists").build();
        this.view.setSidebar(this.page1);
        this.view.setMaxSidebarWidth(300);
        this.view.setShowContent(true);
        this.view.setHexpand(true);
        this.view.setVexpand(true);
        this.view.setHalign(Align.FILL);
        this.view.setValign(Align.BASELINE_FILL);
        this.view.setContent(initialPage);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(view);
    }

    public void setOnAlbumSelected(Consumer<ArtistAlbumInfo> onAlbumSelected) {
        this.onAlbumSelected = onAlbumSelected;
    }

    public void setSelectedArtist(String artistId) {
        this.artistInfoLoader.setArtistId(artistId);
        this.view.setContent(contentPage);
        this.view.setShowContent(true);
    }
}
