package com.github.subsound;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.Icons;
import com.github.subsound.ui.components.PlayerBar;
import com.github.subsound.ui.components.SettingsPage;
import com.github.subsound.ui.views.AlbumInfoLoader;
import com.github.subsound.ui.views.ArtistInfoLoader;
import com.github.subsound.ui.views.ArtistListLoader;
import com.github.subsound.ui.views.ArtistsListBox;
import com.github.subsound.ui.views.FrontpagePage;
import com.github.subsound.ui.views.StarredLoader;
import com.github.subsound.ui.views.TestPlayerPage;
import org.apache.commons.codec.Resources;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.IOUtils;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public class MainApplication {
    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);
    private final AppManager appManager;
    private final ThumbnailCache thumbLoader;
    private final String cssMain;

    private final ViewStack viewStack = ViewStack.builder().build();
    private final NavigationView navigationView = NavigationView.builder().setPopOnEscape(true).setAnimateTransitions(true).build();
    private final ToastOverlay toastOverlay = ToastOverlay.builder().setChild(this.navigationView).build();
    private ToolbarView toolbarView;
    private final HeaderBar headerBar;
    private final ViewSwitcher viewSwitcher;

    private final Button settingsButton;
    private final PlayerBar playerBar;
    private final Box bottomBar;
    private AppNavigation appNavigation;
    private CssProvider mainProvider = CssProvider.builder().build();


    public MainApplication(AppManager appManager) {
        this.appManager = appManager;
        this.appManager.setToastOverlay(this.toastOverlay);
        this.thumbLoader = appManager.getThumbnailCache();
        this.cssMain = mustRead(Path.of("css/main.css"));
        mainProvider.loadFromString(cssMain);
        StyleContext.addProviderForDisplay(Display.getDefault(), mainProvider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);

        settingsButton = Button.builder()
                .setIconName(Icons.Settings.getIconName())
                .setTooltipText("Settings")
                .build();
        settingsButton.onClicked(() -> {
            appNavigation.navigateTo(new AppNavigation.AppRoute.SettingsPage());
        });

        viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

        headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .setShowBackButton(true)
                .build();
        headerBar.packEnd(settingsButton);

        playerBar = new PlayerBar(appManager, (SongInfo songInfo) -> {
            appNavigation.navigateTo(new AppNavigation.AppRoute.RouteArtistInfo(songInfo.artistId()));
        });
        bottomBar = new Box(Orientation.VERTICAL, 2);
        bottomBar.append(playerBar);

        toolbarView = ToolbarView.builder().build();
        toolbarView.addTopBar(headerBar);
        toolbarView.setContent(toastOverlay);
        toolbarView.addBottomBar(bottomBar);

        viewStack.getPages().onSelectionChanged((position, nItems) -> {
            var visibleChild = viewStack.getVisibleChildName();
            System.out.println("viewSwitcher.Pages.SelectionModel.onSelectionChanged.visibleChild: " + visibleChild);
        });
        navigationView.onPopped(page -> {
            log.info("navigationView.pop: page={} child={}", page.getClass().getName(), page.getChild().getClass().getName());
            //boolean canPop = navigationView.getNavigationStack().getNItems() > 1;
            //headerBar.setShowBackButton(canPop);
        });

    }

    public void runActivate(Application app) {
        this.appNavigation = new AppNavigation((appRoute) -> switch (appRoute) {
            case AppNavigation.AppRoute.RouteAlbumsOverview routeAlbumsOverview -> {
                viewStack.setVisibleChildName("albumsPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteArtistInfo routeArtistInfo -> {
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

                var info = cfg.serverConfig == null ? null : new SettingsPage.SettingsInfo(
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
                NavigationPage albumPage = NavigationPage.builder().setChild(viewAlbumPage).setTitle("Album").build();
                //var albumPage = new SubsoundPage(viewAlbumPage, "Album");
                navigationView.push(albumPage);

//                albumInfoContainer.setAlbumId(route.albumId());
//                viewStack.setVisibleChildName("albumInfoPage");
                yield true;
            }
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
            if (appManager.getConfig().isTestpageEnabled) {
                var testPlayerPage = new TestPlayerPage(this.appManager);
                this.appManager.loadSource(testPlayerPage.knownSongs.getFirst().toSongInfo()).join();
                ViewStackPage testPage = viewStack.addTitled(testPlayerPage, "testPage", "Testpage");
            }
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
            starredPageContainer.append(new StarredLoader(
                    thumbLoader,
                    appManager,
                    appNavigation::navigateTo
            ));
            ViewStackPage starredPage = viewStack.addTitledWithIcon(starredPageContainer, "starredPage", "Starred", Icons.Starred.getIconName());
        }
        {
            var artistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            var artistListBox = new ArtistListLoader(
                    thumbLoader,
                    appManager,
                    this.appNavigation::navigateTo
            );
            artistsContainer.append(artistListBox);
            ViewStackPage artistsPage = viewStack.addTitledWithIcon(artistsContainer, "artistsPage", "Artists", Icons.Artist.getIconName());
        }
        {
            ViewStackPage searchPage = viewStack.addTitledWithIcon(searchContainer, "searchPage", "Search", Icons.Search.getIconName());
        }
        {
            ViewStackPage playlistPage = viewStack.addTitledWithIcon(playlistsContainer, "playlistPage", "Playlists", Icons.Playlists.getIconName());
        }

        //viewStack.setVisibleChildName("frontPage");
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

//    public class SubsoundPage extends NavigationPage {
//        public SubsoundPage(Widget child, String title) {
//            super(wrap(child, headerBar, bottomBar), title);
//        }
//
//        private static ToolbarView wrap(Widget child, Widget headerBar, Widget bottomBar) {
//            ToolbarView toolbarView = ToolbarView.builder().build();
//            toolbarView.addTopBar(headerBar);
//            toolbarView.setContent(child);
//            toolbarView.addBottomBar(bottomBar);
//            return toolbarView;
//        }
//    }

    private Box.Builder<? extends Box.Builder> BoxFullsize() {
        return Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .setHexpand(true);
    }

    public static String mustRead(Path cssFile) {
        try {
            var relPath = cssFile.toString();
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readString(Path.of(localFilePath), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] mustReadBytes(String resourcesFilePath) {
        try {
            var relPath = resourcesFilePath;
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readAllBytes(Path.of(localFilePath));
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toByteArray(inputStream);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}