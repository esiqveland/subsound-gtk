package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic.SubsonicClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.views.AlbumInfoLoader;
import io.github.jwharm.javagi.examples.playsound.views.ArtistInfoLoader;
import io.github.jwharm.javagi.examples.playsound.views.ArtistListBox;
import io.github.jwharm.javagi.examples.playsound.views.TestPlayerPage;
import io.github.jwharm.javagi.examples.playsound.views.components.AppNavigation;
import io.github.jwharm.javagi.examples.playsound.views.components.PlayerBar;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.*;
import org.gnome.gdk.Display;
import org.gnome.gtk.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainApplication {
    private final AppManager appManager;
    private final ThumbnailCache thumbLoader;
    private final SubsonicClient client;
    private final String cssMain;

    public MainApplication(AppManager appManager) {
        this.appManager = appManager;
        this.thumbLoader = appManager.getThumbnailCache();
        this.client = appManager.getClient();
        this.cssMain = mustRead(Path.of("src/main/resources/css/main.css"));
    }

    public void runActivate(Application app) {
        var provider = CssProvider.builder().build();
        provider.loadFromString(cssMain);
        StyleContext.addProviderForDisplay(Display.getDefault(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);

        var searchButton = Button.builder()
                .setIconName("edit-find-symbolic")
                .setActionName("app.open-search")
//                .setLabel("Search")
                .build();
        searchButton.onClicked(() -> {
            System.out.println("searchButton.onClicked");
        });

        var settingsButton = Button.builder()
                .setIconName("preferences-other")
                .setActionName("app.open-settings")
                //.setLabel("Settings")
                .setTooltipText("Settings")
                .build();
        settingsButton.actionSetEnabled("app.open-settings", true);
        settingsButton.onActivate(() -> {
            System.out.println("settingsButton.onActivate");
        });
        settingsButton.onClicked(() -> {
            System.out.println("settingsButton.onClicked");
        });

        var searchEntry = SearchEntry.builder().build();
        searchEntry.onSearchChanged(() -> {
            var text = searchEntry.getText();
            System.out.printf("searchEntry.onSearchChanged: %s\n", text);
        });
        var searchBar = SearchBar.builder()
                .setChild(searchEntry)
                .build();

        var frontPageContainer = BoxFullsize().build();

        var searchMe = Button.withLabel("Search me");
        searchMe.onClicked(() -> {
            System.out.println("SearchMe.onClicked");
        });
        var searchContainer = BoxFullsize().build();
        searchContainer.append(searchMe);

        var playlistsMe = Button.withLabel("Playlists");
        playlistsMe.onClicked(() -> {
            System.out.println("playlistsMe.onClicked");
        });
        var playlistsContainer = BoxFullsize().build();
        playlistsContainer.append(playlistsMe);

        ViewStack viewStack = ViewStack.builder().build();
        {
            var testPlayerPage = new TestPlayerPage(this.appManager);
            this.appManager.setSource(testPlayerPage.knownSongs.getFirst().toSongInfo());
            ViewStackPage testPage = viewStack.addTitled(testPlayerPage, "testPage", "Testpage");
        }
        {
            ViewStackPage frontPage = viewStack.addTitled(frontPageContainer, "frontPage", "Home");
        }
        {
            var albumsMe = Button.withLabel("Albums");
            albumsMe.onClicked(() -> {
                System.out.println("albumsMe.onClicked");
            });
            var albumsContainer = BoxFullsize().build();
            albumsContainer.append(albumsMe);
            ViewStackPage albumsPage = viewStack.addTitled(albumsContainer, "albumsPage", "Albums");
        }
        var artists = this.client.getArtists();
        var artistListBox = new ArtistListBox(artists.list());
        {
            var artistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            artistsContainer.append(artistListBox);
            ViewStackPage artistsPage = viewStack.addTitled(artistsContainer, "artistsPage", "Artists");
        }

        var artistContainer = new ArtistInfoLoader(thumbLoader, client);
        {
            var artistId = "7bfaa1b4f3be9ef4f7275de2511da1aa";
            artistContainer.setArtistId(artistId);
            ViewStackPage artistsPage = viewStack.addTitled(artistContainer, "artistInfoPage", "Artist");
        }
        {
            ViewStackPage searchPage = viewStack.addTitled(searchContainer, "searchPage", "Search");
        }
        {
            ViewStackPage playlistPage = viewStack.addTitled(playlistsContainer, "playlistPage", "Playlists");
        }

        var albumInfoContainer = new AlbumInfoLoader(
                thumbLoader,
                client,
                appManager::setSource
        );
        {
            ViewStackPage albumInfoPage = viewStack.addTitled(albumInfoContainer, "albumInfoPage", "AlbumInfo");
        }

        var appNavigation = new AppNavigation((appRoute) -> switch (appRoute) {
            case AppNavigation.AppRoute.RouteAlbumsOverview routeAlbumsOverview -> {
                viewStack.setVisibleChildName("albumsPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteArtistInfo routeArtistInfo -> {
                artistContainer.setArtistId(routeArtistInfo.artistId());
                viewStack.setVisibleChildName("artistInfoPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteArtistsOverview routeArtistsOverview -> {
                viewStack.setVisibleChildName("artistsPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteHome routeHome -> {
                viewStack.setVisibleChildName("testPage");
                yield false;
            }
            case AppNavigation.AppRoute.RouteAlbumInfo route -> {
                albumInfoContainer.setAlbumId(route.albumId());
                viewStack.setVisibleChildName("albumInfoPage");
                yield true;
            }
        });

        artistListBox.onArtistSelected(entry -> appNavigation.navigateTo(new AppNavigation.AppRoute.RouteArtistInfo(entry.id())));
        artistContainer.setOnAlbumSelected(entry -> appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(entry.id())));

        viewStack.getPages().onSelectionChanged((position, nItems) -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.Pages.SelectionModel.onSelectionChanged.visibleChild: " + visibleChild);
        });
        ViewSwitcher viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

        viewSwitcher.onShow(() -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.onShow.visibleChild: " + visibleChild);
        });
        viewSwitcher.onRealize(() -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.onRealize.visibleChild: " + visibleChild);
        });

        ViewSwitcherBar viewSwitcherBar = ViewSwitcherBar.builder()
                .setStack(viewStack)
                .build();

        var headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .build();
        headerBar.packStart(searchButton);
        headerBar.packEnd(settingsButton);

        var playerBar = new PlayerBar(thumbLoader, appManager);
        var bottomBar = new Box(Orientation.VERTICAL, 2);
        bottomBar.append(viewSwitcherBar);
        bottomBar.append(playerBar);

        ToolbarView toolbarView = ToolbarView.builder().build();
        toolbarView.addTopBar(headerBar);
        toolbarView.setContent(viewStack);
        toolbarView.addBottomBar(bottomBar);
        //toolbarView.addBottomBar(viewSwitcherBar);

        // Pack everything together, and show the window
        var window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(1000).setDefaultHeight(700)
                .setContent(toolbarView)
                .build();

        window.present();
    }

    private Box.Builder<? extends Box.Builder> BoxFullsize() {
        return Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .setHexpand(true);
    }

    private static String mustRead(Path cssFile) {
        try {
            return Files.readString(cssFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}