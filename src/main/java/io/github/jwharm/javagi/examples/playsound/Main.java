package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.base.Out;
import io.github.jwharm.javagi.examples.playsound.sound.SoundPlayer;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.*;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.*;

import static io.github.jwharm.javagi.examples.playsound.sound.SoundPlayer.FILENAME;

public class Main {
    private final SoundPlayer player;

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(args));

        this.player = new SoundPlayer(new String[]{FILENAME});

        try {
            Application app = new Application("com.gitlab.subsound.player", ApplicationFlags.DEFAULT_FLAGS);
            app.onActivate(() -> onActivate(app));
            app.onShutdown(this.player::quit);
            app.run(args);
        } finally {
            this.player.quit();
        }
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

        ViewStack viewStack = ViewStack.builder().build();

        var searchEntry = SearchEntry.builder().build();
        searchEntry.onSearchChanged(() -> {
            var text = searchEntry.getText();
            System.out.printf("searchEntry.onSearchChanged: %s\n", text);
        });
        var searchBar = SearchBar.builder()
                .setChild(searchEntry)
                .build();
        var helloWorld = Button.withLabel("Hello World");
        helloWorld.onClicked(() -> {
            System.out.println("helloWorld.onClicked");
        });
        var playButton = Button.withLabel("Play");
        playButton.onClicked(() -> {
            System.out.println("playButton.onClicked");
            player.play();
        });
        var pauseButton = Button.withLabel("Pause");
        pauseButton.onClicked(() -> {
            System.out.println("pauseButton.onClicked");
            player.pause();
        });

        var searchMe = Button.withLabel("Search me");
        searchMe.onClicked(() -> {
            System.out.println("SearchMe.onClicked");
        });

        var frontpageContainer = BoxFullsize()
                .build();
        frontpageContainer.append(searchBar);
        frontpageContainer.append(helloWorld);
        frontpageContainer.append(playButton);
        frontpageContainer.append(pauseButton);

        var searchContainer = BoxFullsize()
                .build();
        searchContainer.append(searchBar);
        searchContainer.append(searchMe);

        var albumsMe = Button.withLabel("Albums");
        albumsMe.onClicked(() -> {
            System.out.println("albumsMe.onClicked");
        });
        var albumsContainer = BoxFullsize()
                .build();
        albumsContainer.append(searchBar);
        albumsContainer.append(albumsMe);

        var playlistsMe = Button.withLabel("Playlists");
        playlistsMe.onClicked(() -> {
            System.out.println("playlistsMe.onClicked");
        });
        var playlistsContainer = BoxFullsize()
                .build();
        playlistsContainer.append(searchBar);
        playlistsContainer.append(playlistsMe);

        {
            ViewStackPage frontPage = viewStack.addTitled(frontpageContainer, "frontPage", "Home");
        }
        {
            ViewStackPage albumsPage = viewStack.addTitled(albumsContainer, "albumsPage", "Albums");
        }
        {
            ViewStackPage searchPage = viewStack.addTitled(searchContainer, "searchPage", "Search");
        }
        {
            ViewStackPage playlistPage = viewStack.addTitled(playlistsContainer, "playlistPage", "Playlists");
        }

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

        var playerBar = new PlayerBar();
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

