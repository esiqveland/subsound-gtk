package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.ui.components.AppNavigation;
import io.github.jwharm.javagi.examples.playsound.ui.components.Icons;
import io.github.jwharm.javagi.examples.playsound.ui.components.PlayerBar;
import io.github.jwharm.javagi.examples.playsound.ui.components.SettingsPage;
import io.github.jwharm.javagi.examples.playsound.ui.views.AlbumInfoLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.ArtistInfoLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.ArtistsListBox;
import io.github.jwharm.javagi.examples.playsound.ui.views.FrontpagePage;
import io.github.jwharm.javagi.examples.playsound.ui.views.StarredLoader;
import io.github.jwharm.javagi.examples.playsound.ui.views.TestPlayerPage;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationView;
import org.gnome.adw.ToastOverlay;
import org.gnome.adw.ToolbarView;
import org.gnome.adw.ViewStack;
import org.gnome.adw.ViewStackPage;
import org.gnome.adw.ViewSwitcher;
import org.gnome.adw.ViewSwitcherPolicy;
import org.gnome.gdk.Display;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.StyleContext;
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
    private final ServerClient client;
    private final String cssMain;

    private final ViewStack viewStack = ViewStack.builder().build();
    private final NavigationView navigationView = NavigationView.builder().build();
    private final ToastOverlay toastOverlay = ToastOverlay.builder().setChild(navigationView).build();
    private AppNavigation appNavigation;
    private ArtistInfoLoader artistContainer;

    public MainApplication(AppManager appManager) {
        this.appManager = appManager.setToastOverlay(toastOverlay);
        this.thumbLoader = appManager.getThumbnailCache();
        this.client = appManager.getClient();
        this.cssMain = mustRead(Path.of("src/main/resources/css/main.css"));
        this.artistContainer = new ArtistInfoLoader(
                thumbLoader,
                this.appManager,
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
            case AppNavigation.AppRoute.RouteArtistInfo2 routeArtistInfo -> {
                var content = new ArtistInfoLoader(this.thumbLoader, this.appManager, albumInfo -> {
                    appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()));
                });
                content.setArtistId(routeArtistInfo.artistId());
                var albumPage = NavigationPage.builder().setChild(content).build();
                navigationView.push(albumPage);
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
            case AppNavigation.AppRoute.SettingsPage s -> {
                var cfg = this.appManager.getConfig();
                var info = new SettingsPage.SettingsInfo(
                        cfg.serverConfig.type(),
                        cfg.serverConfig.url(),
                        cfg.serverConfig.username(),
                        cfg.serverConfig.password()
                );
                var settings = new SettingsPage(
                        info,
                        appManager::handleAction
                );
                NavigationPage navPage = NavigationPage.builder().setChild(settings).setTitle("Settings").build();
                navigationView.push(navPage);

                yield true;
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


        var settingsButton = Button.builder()
                .setIconName(Icons.Settings.getIconName())
                .setTooltipText("Settings")
                .build();
        settingsButton.onClicked(() -> {
            appNavigation.navigateTo(new AppNavigation.AppRoute.SettingsPage());
        });


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
            ViewStackPage frontPage = viewStack.addTitledWithIcon(frontPageContainer, "frontPage", "Home", Icons.GoHome.getIconName());
        }
        {
            var starredPageContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            starredPageContainer.append(new StarredLoader(thumbLoader, appManager));
            ViewStackPage starredPage = viewStack.addTitledWithIcon(starredPageContainer, "starredPage", "Starred", Icons.Starred.getIconName());
        }
        var artists = this.client.getArtists();
        var artistListBox = new ArtistsListBox(
                thumbLoader,
                appManager,
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

        viewStack.getPages().onSelectionChanged((position, nItems) -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.Pages.SelectionModel.onSelectionChanged.visibleChild: " + visibleChild);
        });
        ViewSwitcher viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

        var headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .build();
        headerBar.packEnd(settingsButton);

        var playerBar = new PlayerBar(thumbLoader, appManager, (SongInfo songInfo) -> {
            appNavigation.navigateTo(new AppNavigation.AppRoute.RouteArtistInfo2(songInfo.artistId()));
        });
        bottomBar = new Box(Orientation.VERTICAL, 2);
        bottomBar.append(playerBar);

        navigationView.onPopped(page -> {
            log.info("navigationView.pop: page={} child={}", page.getClass().getName(), page.getChild().getClass().getName());
        });

        ToolbarView toolbarView = ToolbarView.builder().build();
        toolbarView.addTopBar(headerBar);
        toolbarView.setContent(toastOverlay);
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