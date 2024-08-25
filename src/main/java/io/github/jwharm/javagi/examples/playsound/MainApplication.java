package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic.SubsonicClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.views.AlbumInfoLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.ArtistInfoLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.ArtistsListBox;
import io.github.jwharm.javagi.examples.playsound.ui.views.FrontpagePage;
import io.github.jwharm.javagi.examples.playsound.ui.views.StarredLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.TestPlayerPage;
import io.github.jwharm.javagi.examples.playsound.ui.components.AppNavigation;
import io.github.jwharm.javagi.examples.playsound.ui.components.Icons;
import io.github.jwharm.javagi.examples.playsound.ui.components.PlayerBar;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.*;
import org.gnome.gdk.Display;
import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainApplication {
    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);
    private final AppManager appManager;
    private final ThumbnailCache thumbLoader;
    private final SubsonicClient client;
    private final String cssMain;

    private final ViewStack viewStack = ViewStack.builder().build();
    private final NavigationView navigationView = NavigationView.builder().build();
    private AppNavigation appNavigation;
    private ArtistInfoLoader artistContainer;

    public MainApplication(AppManager appManager) {
        this.appManager = appManager;
        this.thumbLoader = appManager.getThumbnailCache();
        this.client = appManager.getClient();
        this.cssMain = mustRead(Path.of("src/main/resources/css/main.css"));
        this.artistContainer = new ArtistInfoLoader(
                thumbLoader,
                client,
                albumInfo -> this.appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()))
        );
    }

    public void runActivate(Application app) {
        var provider = CssProvider.builder().build();
        provider.loadFromString(cssMain);
        StyleContext.addProviderForDisplay(Display.getDefault(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);

        this.appNavigation = new AppNavigation((appRoute) -> switch (appRoute) {
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
            case AppNavigation.AppRoute.RouteStarred starred -> {
                viewStack.setVisibleChildName("starredPage");
                yield false;
            }
            case AppNavigation.AppRoute.RouteAlbumInfo route -> {
                var viewAlbumPage = new AlbumInfoLoader(this.thumbLoader, this.appManager, appManager::handleAction)
                        .setAlbumId(route.albumId());
                NavigationPage albumPage = NavigationPage.builder().setChild(viewAlbumPage).setTitle("%s".formatted(route.albumId())).build();
                navigationView.push(albumPage);

//                albumInfoContainer.setAlbumId(route.albumId());
//                viewStack.setVisibleChildName("albumInfoPage");
                yield true;
            }
        });

        var searchButton = Button.builder()
                .setIconName(Icons.SearchEdit.getIconName())
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

        {
            var testPlayerPage = new TestPlayerPage(this.appManager);
            this.appManager.loadSource(testPlayerPage.knownSongs.getFirst().toSongInfo()).join();
            ViewStackPage testPage = viewStack.addTitled(testPlayerPage, "testPage", "Testpage");
        }
        {
            var frontPageContainer = new FrontpagePage(
                    thumbLoader,
                    appManager,
                    albumInfo -> this.appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()))
            );
            ViewStackPage frontPage = viewStack.addTitled(frontPageContainer, "frontPage", "Home");
        }
        {
            var starredPageContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            starredPageContainer.append(new StarredLoader(thumbLoader, client, appManager::loadSource, appManager::handleAction));
            ViewStackPage starredPage = viewStack.addTitled(starredPageContainer, "starredPage", "Starred");
        }
        var artists = this.client.getArtists();
        var artistListBox = new ArtistsListBox(
                thumbLoader,
                client,
                artists.list(),
                albumInfo -> this.appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()))
        );
        {
            var artistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            artistsContainer.append(artistListBox);
            ViewStackPage artistsPage = viewStack.addTitledWithIcon(artistsContainer, "artistsPage", "Artists", Icons.Artist.getIconName());
        }

        {
            var artistId = "7bfaa1b4f3be9ef4f7275de2511da1aa";
            artistContainer.setArtistId(artistId);
            ViewStackPage artistsPage = viewStack.addTitledWithIcon(artistContainer, "artistInfoPage", "Artist", Icons.ARTIST_ALBUM.getIconName());
        }
        {
            ViewStackPage searchPage = viewStack.addTitledWithIcon(searchContainer, "searchPage", "Search", Icons.Search.getIconName());
        }
        {
            ViewStackPage playlistPage = viewStack.addTitledWithIcon(playlistsContainer, "playlistPage", "Playlists", Icons.Playlists.getIconName());
        }

        var albumInfoContainer = new AlbumInfoLoader(
                thumbLoader,
                appManager,
                appManager::handleAction
        );
        {
            ViewStackPage albumInfoPage = viewStack.addTitledWithIcon(albumInfoContainer, "albumInfoPage", "AlbumInfo", Icons.Albums.getIconName());
        }

        viewStack.getPages().onSelectionChanged((position, nItems) -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.Pages.SelectionModel.onSelectionChanged.visibleChild: " + visibleChild);
        });
        ViewSwitcher viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

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

        navigationView.onPopped(page -> {
            log.info("navigationView.pop: page={} child={}", page.getClass().getName(), page.getChild().getClass().getName());
        });

        ToolbarView toolbarView = ToolbarView.builder().build();
        toolbarView.addTopBar(headerBar);
        toolbarView.setContent(navigationView);
        toolbarView.addBottomBar(bottomBar);

        var mainPage = NavigationPage.builder().setChild(viewStack).setTag("main").build();
        navigationView.push(mainPage);

        // Pack everything together, and show the window
        var window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(1040).setDefaultHeight(780)
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