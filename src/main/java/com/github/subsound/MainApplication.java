package com.github.subsound;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.Classes;
import com.github.subsound.ui.components.Icons;
import com.github.subsound.ui.components.OnboardingOverlay;
import com.github.subsound.ui.components.PlayerBar;
import com.github.subsound.ui.components.ServerBadge;
import com.github.subsound.ui.components.SettingsPage;
import com.github.subsound.ui.views.AlbumInfoLoader;
import com.github.subsound.ui.views.ArtistInfoLoader;
import com.github.subsound.ui.views.ArtistListLoader;
import com.github.subsound.ui.views.FrontpagePage;
import com.github.subsound.ui.views.PlaylistsViewLoader;
import com.github.subsound.ui.views.StarredLoader;
import com.github.subsound.ui.views.StarredPage;
import com.github.subsound.ui.views.TestPlayerPage;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.ColorScheme;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationView;
import org.gnome.adw.StyleManager;
import org.gnome.adw.ToastOverlay;
import org.gnome.adw.ToolbarView;
import org.gnome.adw.ViewStack;
import org.gnome.adw.ViewStackPage;
import org.gnome.adw.ViewSwitcher;
import org.gnome.adw.ViewSwitcherPolicy;
import org.gnome.gdk.Display;
import org.gnome.glib.Variant;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CallbackAction;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Shortcut;
import org.gnome.gtk.ShortcutController;
import org.gnome.gtk.ShortcutScope;
import org.gnome.gtk.ShortcutTrigger;
import org.gnome.gtk.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

import static com.github.subsound.utils.Utils.mustRead;

public class MainApplication {
    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);
    private final AppManager appManager;
    private final ThumbnailCache thumbLoader;
    private final String cssMain;
    private ApplicationWindow window;
    private WindowSize lastWindowSize;

    private final ViewStack viewStack = ViewStack.builder().build();
    private final Button backButton;
    private final NavigationView navigationView = NavigationView.builder().setPopOnEscape(true).setAnimateTransitions(true).build();
    private final ToastOverlay toastOverlay = ToastOverlay.builder().setChild(this.navigationView).build();
    private ToolbarView toolbarView;
    private final HeaderBar headerBar;
    private final ViewSwitcher viewSwitcher;

    private final MenuButton settingsButton;
    private final PlayerBar playerBar;
    private final Box bottomBar;
    private final Shortcut playPauseShortcut;
    private final ServerBadge serverBadge;
    private AppNavigation appNavigation;
    private final CssProvider mainProvider = CssProvider.builder().build();


    public MainApplication(AppManager appManager) {
        this.appManager = appManager;
        this.appManager.setToastOverlay(this.toastOverlay);
        this.thumbLoader = appManager.getThumbnailCache();
        this.cssMain = mustRead(Path.of("css/main.css"));
        mainProvider.loadFromString(cssMain);
        Gtk.styleContextAddProviderForDisplay(Display.getDefault(), mainProvider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        // force to dark mode as we currently look terrible in light mode:
        StyleManager.getDefault().setColorScheme(ColorScheme.FORCE_DARK);

        //var playPauseTrigger = ShortcutTrigger.parseString("<Control>KP_Space");
        var playPauseTrigger = ShortcutTrigger.parseString("<Control>p");
        var playPauseAction = new CallbackAction((Widget widget, @Nullable Variant args) -> {
            log.info("callback action");
            if (this.appManager.getState().player().state().isPlaying()) {
                this.appManager.pause();
            } else {
                this.appManager.play();
            }
            return true;
        });
        playPauseShortcut = Shortcut.builder().setTrigger(playPauseTrigger).setAction(playPauseAction).build();

        // Create popover menu content
        var popoverContent = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(8)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        // Server badge at the top
        this.serverBadge = new ServerBadge(appManager);
        popoverContent.append(serverBadge);

        var configureServerButton = Button.builder()
                .setLabel("Server settings...")
                .build();
        configureServerButton.addCssClass("flat");
        popoverContent.append(configureServerButton);

        var settingsPopover = Popover.builder()
                .setChild(popoverContent)
                .build();
        settingsPopover.onShow(serverBadge::refresh);

        configureServerButton.onClicked(() -> {
            settingsPopover.popdown();
            appNavigation.navigateTo(new AppNavigation.AppRoute.SettingsPage());
        });

        settingsButton = MenuButton.builder()
                .setIconName(Icons.Settings.getIconName())
                .setTooltipText("Settings")
                .setPopover(settingsPopover)
                .build();

        viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

        this.backButton = Button.builder()
                .setIconName("go-previous-symbolic")
                .setTooltipText("Back")
                .build();
        this.backButton.addCssClass(Classes.flat.className());
        this.backButton.setVisible(false);
        this.backButton.onClicked(() -> navigationView.pop());

        headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .setShowBackButton(true)
                .build();
        headerBar.packStart(this.backButton);
        headerBar.packEnd(settingsButton);
        // TODO: these classes were an attempt to find better background colors to blend different parts of the UI
        //headerBar.addCssClass("background");
        //headerBar.addCssClass("view");

        playerBar = new PlayerBar(appManager);
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
        navigationView.onPushed(() -> {
            int size = navigationView.getNavigationStack().getNItems();
            log.info("navigationView.push: size={}", size);
            boolean canPop = size > 1;
            this.backButton.setVisible(canPop);
            //headerBar.setShowBackButton(canPop);
        });

        navigationView.onPopped(page -> {
            int size = navigationView.getNavigationStack().getNItems();
            log.info("navigationView.pop: size={} page={} child={}", size, page.getClass().getName(), page.getChild().getClass().getName());
            boolean canPop = size > 1;
            //  When navigating to an album or artist page via RouteAlbumInfo or RouteArtistInfo, a NavigationPage is pushed onto the NavigationView,
            //  but there's no back button in the header bar to pop the page.
            //  The AdwHeaderBar is placed outside the NavigationView in a global ToolbarView.
            //  The automatic back button behavior from libadwaita only works when HeaderBar is inside each NavigationPage.
            //  Moving it inside would require restructuring and duplicating the HeaderBar for each page view,
            //  so I am not certain of the trade-offs here. Its also very nice that the HeaderBar is always the same HeaderBar.
            this.backButton.setVisible(canPop);
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
            case AppNavigation.AppRoute.RoutePlaylistsOverview route -> {
                viewStack.setVisibleChildName("playlistPage");
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
                        cfg.dataDir,
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
        this.appManager.setNavigator(this.appNavigation);
        var cfg = this.appManager.getConfig();

        {
            if (appManager.getConfig().isTestpageEnabled) {
                var testPlayerPage = new TestPlayerPage(this.appManager);
                if (!testPlayerPage.knownSongs.isEmpty()) {
                    var songInfo = testPlayerPage.knownSongs.getFirst().toSongInfo();
                    this.appManager.loadSource(new PlayerAction.PlaySong(songInfo)).join();
                }
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
            var starredPageComponent = new StarredPage();
            starredPageComponent.append(new StarredLoader(
                    thumbLoader,
                    appManager,
                    appNavigation::navigateTo
            ));
            ViewStackPage starredPage = viewStack.addTitledWithIcon(starredPageComponent, "starredPage", "Starred", Icons.Starred.getIconName());
        }
        {
            var playlistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            playlistsContainer.append(new PlaylistsViewLoader(
                    thumbLoader,
                    appManager,
                    appNavigation::navigateTo
            ));
            ViewStackPage starredPage = viewStack.addTitledWithIcon(playlistsContainer, "playlistPage", "Playlists", Icons.Starred.getIconName());
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
//        {
//            var searchMe = Button.withLabel("Search me");
//            searchMe.onClicked(() -> {
//                System.out.println("SearchMe.onClicked");
//            });
//            var searchContainer = BoxFullsize().build();
//            searchContainer.append(searchMe);
//            ViewStackPage searchPage = viewStack.addTitledWithIcon(searchContainer, "searchPage", "Search", Icons.Search.getIconName());
//        }
//        {
//            var playlistsMe = Button.withLabel("Playlists");
//            playlistsMe.onClicked(() -> {
//                System.out.println("playlistsMe.onClicked");
//            });
//            var playlistsContainer = BoxFullsize().build();
//            playlistsContainer.append(playlistsMe);
//            ViewStackPage playlistPage = viewStack.addTitledWithIcon(playlistsContainer, "playlistPage", "Playlists", Icons.Playlists.getIconName());
//        }

        if (cfg.onboarding != OnboardingState.DONE) {
            var onboardingOverlay = getOnboardingOverlay(this.appManager, viewStack);
            var onboardingPage = NavigationPage.builder().setChild(onboardingOverlay).setTag("onboardingOverlay").build();
            navigationView.push(onboardingPage);
        } else {
            var mainPage = NavigationPage.builder().setChild(viewStack).setTag("main").build();
            navigationView.push(mainPage);
        }

        var shortcutController = new ShortcutController();
        shortcutController.addShortcut(playPauseShortcut);
        shortcutController.setScope(ShortcutScope.GLOBAL);

        // Pack everything together, and show the window
        this.window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(cfg.windowWidth).setDefaultHeight(cfg.windowHeight)
                .setContent(toolbarView)
                .build();
        window.addController(shortcutController);

        // Capture window size before it's destroyed
        window.onCloseRequest(() -> {
            this.lastWindowSize = new WindowSize(window.getWidth(), window.getHeight());
            return false; // Allow window to close
        });

        window.present();
    }

    @NotNull
    private OnboardingOverlay getOnboardingOverlay(AppManager appManager, ViewStack viewStack) {
        return new OnboardingOverlay(appManager, viewStack);
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

    public static Box.Builder<? extends Box.Builder> BoxFullsize() {
        return Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .setHexpand(true);
    }

    public record WindowSize(int width, int height) {}

    public WindowSize getLastWindowSize() {
        return lastWindowSize;
    }

    public void shutdown() {
        this.serverBadge.shutdown();
    }
}