package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.PlaylistSimple;
import com.github.subsound.ui.views.StarredListView;
import com.github.subsound.ui.views.StarredLoader.PlaylistsData;
import org.gnome.adw.ActionRow;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.*;

import java.util.Map;
import java.util.stream.Collectors;

import static com.github.subsound.utils.Utils.cssClasses;

public class PlaylistsListView extends Box {
    private final AppManager appManager;
    private final PlaylistsData data;
    private final Map<String, PlaylistSimple> cache;
    private final ListBox list;
    private final NavigationSplitView view;
    private final NavigationPage initialPage;
    private final NavigationPage page1;
    private final NavigationPage contentPage;
    private final StarredListView starredListView;

    public PlaylistsListView(AppManager appManager, PlaylistsData data) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.data = data;
        this.cache = this.data.playlistList().playlists().stream()
                .collect(Collectors.toMap(PlaylistSimple::id, a -> a));

        this.starredListView = new StarredListView(data.starredList(), this.appManager.getThumbnailCache(), this.appManager, this.appManager::navigateTo);
        this.contentPage = NavigationPage.builder().setTag("page-2").setChild(this.starredListView).setTitle("Starred").build();
        var b = Box.builder().setValign(Align.CENTER).setHalign(Align.CENTER).build();
        b.append(Label.builder().setLabel("Select a playlist to view").setCssClasses(cssClasses("title-1")).build());
        var statusPage = StatusPage.builder().setChild(b).build();
        this.initialPage = NavigationPage.builder().setTag("page-2-initial").setChild(statusPage).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.view = NavigationSplitView.builder().setValign(Align.FILL).setHalign(Align.FILL).setHexpand(true).setVexpand(true).build();

        list = ListBox.builder().setValign(Align.START).setVexpand(true).build();
        list.addCssClass(Classes.boxedList.className());
        list.onRowActivated(row -> {
            var playlist = this.data.playlistList().playlists().get(row.getIndex());
            System.out.println("PlaylistsListView: goto " + playlist.name());
            //this.contentPage.setTitle(playlist.name());
            //this.setSelectedArtist(playlist.id());
        });

        var stringList = StringList.builder().build();
        this.data.playlistList().playlists().forEach(i -> stringList.append(i.id()));
        list.bindModel(stringList, item -> {
            // StringObject is the item type for a StringList ListModel type. StringObject is a GObject.
            StringObject strObj = (StringObject) item;
            var id = strObj.getString();
            var playlist = this.cache.get(id);
            var row = ActionRow.builder()
                    .setTitle(playlist.name())
                    .setTitleLines(1)
                    .setSubtitle(playlist.songCount() + " items")
                    .setUseMarkup(false)
                    .setActivatable(true)
                    .build();
            row.addPrefix(RoundedAlbumArt.resolveCoverArt(
                    appManager.getThumbnailCache(),
                    playlist.coverArtId(),
                    48
            ));
            return row;
        });

        var playlistView = ScrolledWindow.builder().setChild(list).setHexpand(true).setVexpand(true).build();
        // https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/migrating-to-breakpoints.html#sidebar
        this.page1 = NavigationPage.builder().setTag("page-1").setChild(playlistView).setTitle("Playlists").build();
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
}
