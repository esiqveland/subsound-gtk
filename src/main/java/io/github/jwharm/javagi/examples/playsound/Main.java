package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.base.Out;
import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.components.*;
import io.github.jwharm.javagi.examples.playsound.components.AppNavigation.AppRoute;
import io.github.jwharm.javagi.examples.playsound.configuration.Config;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.CoverArt;
import io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic.SubsonicClient;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import net.beardbot.subsonic.client.SubsonicPreferences;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.*;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.*;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.net.URI;

public class Main {
    static {
        // Bridge/route all JUL log records to the SLF4J API.
        SLF4JBridgeHandler.install();
    }
    private final Config config;
    private final PlaybinPlayer player;
    private final SubsonicClient client;
    private final AppManager appManager;

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(args));
        this.config = Config.createDefault();
        var songCache = new SongCache(this.config.cacheDir);
        this.player = new PlaybinPlayer();
        this.appManager = new AppManager(player, songCache);
        this.client = SubsonicClient.create(getSubsonicSettings(config));

        try {
            Application app = new Application("com.subsound.player", ApplicationFlags.DEFAULT_FLAGS);
            app.onActivate(() -> onActivate(app));
            app.onShutdown(this.player::quit);
            app.run(args);
        } finally {
            this.player.quit();
        }
    }

    private SubsonicPreferences getSubsonicSettings(Config config) {
        SubsonicPreferences preferences = new SubsonicPreferences(
                config.serverConfig.url(),
                config.serverConfig.username(),
                config.serverConfig.password()
        );
        preferences.setStreamBitRate(192);
        preferences.setClientName("Subsound");
        return preferences;
    }

    private void onActivate(Application app) {
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
            var testPlayerPage = new TestPlayerPage(this.player);
            this.player.setSource(testPlayerPage.knownSongs.getFirst().uri());
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
            var albumImg = new AlbumArt(new CoverArt(
                    "kbW_38ngyv4bR9IeBP23KpMp3yfo4Hi7yLZrFNckVGk",
                    URI.create("https://play.logisk.org/share/img/eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpZCI6ImFyLTczMzJiMWFlMTNmZjhjYmUzYTNlOTFkOGQwNGMzODk1XzAiLCJpc3MiOiJORCJ9.kbW_38ngyv4bR9IeBP23KpMp3yfo4Hi7yLZrFNckVGk?size=600")
            ));
            var albumsContainer = BoxFullsize().build();
            albumsContainer.append(albumsMe);
            albumsContainer.append(albumImg);
            ViewStackPage albumsPage = viewStack.addTitled(albumsContainer, "albumsPage", "Albums");
        }
        var artists = this.client.getArtists();
        var artistListBox = new ArtistListBox(artists.list());
        {
            var artistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            artistsContainer.append(artistListBox);
            ViewStackPage artistsPage = viewStack.addTitled(artistsContainer, "artistsPage", "Artists");
        }

        var artistContainer = new ArtistInfoLoader(client);
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
                client,
                songInfo -> {

                }
                );
        {
            ViewStackPage albumInfoPage = viewStack.addTitled(albumInfoContainer, "albumInfoPage", "AlbumInfo");
        }

        var appNavigation = new AppNavigation((appRoute) -> switch (appRoute) {
            case AppRoute.RouteAlbumsOverview routeAlbumsOverview -> {
                viewStack.setVisibleChildName("albumsPage");
                yield true;
            }
            case AppRoute.RouteArtistInfo routeArtistInfo -> {
                artistContainer.setArtistId(routeArtistInfo.artistId());
                viewStack.setVisibleChildName("artistInfoPage");
                yield true;
            }
            case AppRoute.RouteArtistsOverview routeArtistsOverview -> {
                viewStack.setVisibleChildName("artistsPage");
                yield true;
            }
            case AppRoute.RouteHome routeHome -> {
                viewStack.setVisibleChildName("testPage");
                yield false;
            }
            case AppRoute.RouteAlbumInfo route -> {
                albumInfoContainer.setAlbumId(route.albumId());
                viewStack.setVisibleChildName("albumInfoPage");
                yield true;
            }
        });

        artistListBox.onArtistSelected(entry -> appNavigation.navigateTo(new AppRoute.RouteArtistInfo(entry.id())));
        artistContainer.setOnAlbumSelected(entry -> appNavigation.navigateTo(new AppRoute.RouteAlbumInfo(entry.id())));

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

        var playerBar = new PlayerBar(player);
        var bottomBar = new Box(Orientation.VERTICAL, 10);
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

    public static void main(String[] args) {
        new Main(args);
    }
}

