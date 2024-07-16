package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.base.Out;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.*;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.*;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class Main {
    private final PlaybinPlayer player;
    private final List<URI> knownSongs = loadSamples("src/main/resources/samples");

    private List<URI> loadSamples(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("must be a directory with music files: " + dirPath);
        }
        File[] files = dir.listFiles(File::isFile);
        return Stream.of(files).map(File::getAbsoluteFile).map(File::toURI).toList();
    }

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(args));

        //this.player = new SoundPlayer(new String[]{FILENAME});
        this.player = new PlaybinPlayer(knownSongs.get(0));

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

        var volumeHalf = Button.withLabel("Volume 0.5");
        volumeHalf.onClicked(() -> {
            System.out.println("volumeHalf.onClicked");
            player.setVolume(0.50);
        });

        var volumeQuarter = Button.withLabel("Volume 0.25");
        volumeQuarter.onClicked(() -> {
            System.out.println("volumeQuarter.onClicked");
            player.setVolume(0.250);
        });

        var volumeFull = Button.withLabel("Volume 1.0");
        volumeFull.onClicked(() -> {
            System.out.println("volumeFull.onClicked");
            player.setVolume(1.0);
        });
        var muteOnButton = Button.withLabel("Mute On");
        muteOnButton.onClicked(() -> {
            System.out.println("muteOnButton.onClicked");
            player.setMute(true);
        });
        var muteOffButton = Button.withLabel("Mute Off");
        muteOffButton.onClicked(() -> {
            System.out.println("muteOffButton.onClicked");
            player.setMute(false);
        });

        List<Button> songButtons = knownSongs.stream().map(songUri -> {
            var path = Path.of(songUri);
            var btnName = path.getFileName().toString();
            var btn = Button.withLabel(btnName);
            btn.onClicked(() -> {
                System.out.println("Btn: change source to=" + btnName);
                this.player.setSource(songUri);
            });
            return btn;
        }).toList();

        var testPageBox = BoxFullsize().build();
        testPageBox.append(searchBar);
        testPageBox.append(helloWorld);
        testPageBox.append(playButton);
        testPageBox.append(pauseButton);
        songButtons.forEach(testPageBox::append);
        testPageBox.append(muteOnButton);
        testPageBox.append(muteOffButton);
        testPageBox.append(volumeFull);
        testPageBox.append(volumeHalf);
        testPageBox.append(volumeQuarter);

        var frontPageBox = BoxFullsize().build();

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
            ViewStackPage testPage = viewStack.addTitled(testPageBox, "testPage", "Testpage");
        }
        {
            ViewStackPage frontPage = viewStack.addTitled(frontPageBox, "frontPage", "Home");
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

