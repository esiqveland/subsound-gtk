package com.github.subsound;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.Icons;
import com.github.subsound.ui.components.OnboardingOverlay;
import com.github.subsound.ui.components.PlayerBar;
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
import org.gnome.gtk.Label;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Shortcut;
import org.gnome.gtk.ShortcutController;
import org.gnome.gtk.ShortcutScope;
import org.gnome.gtk.ShortcutTrigger;
import org.gnome.gtk.StyleContext;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import static com.github.subsound.utils.Utils.mustRead;

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

    private final MenuButton settingsButton;
    private final PlayerBar playerBar;
    private final Box bottomBar;
    private final Shortcut playPauseShortcut;
    private AppNavigation appNavigation;
    private CssProvider mainProvider = CssProvider.builder().build();


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
        var serverHostLabel = Label.builder()
                .setLabel(getServerHostNameOrNotConnected())
                .setHalign(Align.START)
                .setEllipsize(EllipsizeMode.END)
                .build();
        var serverBadge = createServerBadge(serverHostLabel);
        popoverContent.append(serverBadge);

        var configureServerButton = Button.builder()
                .setLabel("Server settings...")
                .build();
        configureServerButton.addCssClass("flat");
        popoverContent.append(configureServerButton);

        var settingsPopover = Popover.builder()
                .setChild(popoverContent)
                .build();
        settingsPopover.onShow(() -> {
            serverHostLabel.setLabel(getServerHostNameOrNotConnected());
        });

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

        headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .setShowBackButton(true)
                .build();
        headerBar.packEnd(settingsButton);

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
                    this.appManager.loadSource(testPlayerPage.knownSongs.getFirst().toSongInfo()).join();
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
        var window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(1040).setDefaultHeight(850)
                .setContent(toolbarView)
                .build();
        window.addController(shortcutController);
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

    private String getServerHostNameOrNotConnected() {
        return getServerHostName().orElse("Not connected");
    }

    private Optional<String> getServerHostName() {
        var cfg = this.appManager.getConfig();
        String serverHost = null;
        if (cfg.serverConfig != null && cfg.serverConfig.url() != null && !cfg.serverConfig.url().isBlank()) {
            try {
                URI uri = URI.create(cfg.serverConfig.url());
                serverHost = uri.getHost();
                if (serverHost == null) {
                    serverHost = cfg.serverConfig.url();
                } else {
                    var parts = serverHost.split(":");
                    serverHost = parts[0];
                }
            } catch (Exception e) {
                serverHost = cfg.serverConfig.url();
            }
        }
        return Optional.ofNullable(serverHost);
    }

    private Widget createServerBadge(Label serverLabel) {
        var titleLabel = Label.builder()
                .setLabel("Connected to")
                .setHalign(Align.START)
                .build();
        titleLabel.addCssClass("dim-label");
        titleLabel.addCssClass("caption");

        var textBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(2)
                .build();
        textBox.append(titleLabel);
        textBox.append(serverLabel);

        var icon = org.gnome.gtk.Image.fromIconName(Icons.NetworkServer.getIconName());
        icon.setIconSize(org.gnome.gtk.IconSize.LARGE);

        var badge = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setSpacing(12)
                .setMarginTop(8)
                .setMarginBottom(8)
                .setMarginStart(12)
                .setMarginEnd(12)
                .build();
        badge.append(icon);
        badge.append(textBox);

        return badge;
    }
}