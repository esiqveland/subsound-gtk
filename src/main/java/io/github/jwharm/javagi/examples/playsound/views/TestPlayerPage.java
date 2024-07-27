package io.github.jwharm.javagi.examples.playsound.views;

import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Orientation;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.sha256;

public class TestPlayerPage extends Box {
    // TODO: move the test player page here
    public final List<Sample> knownSongs ;
    private final AppManager player;

    public TestPlayerPage(AppManager player) {
        super(Orientation.VERTICAL, 4);
        this.player = player;
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);
        this.setVexpand(true);
        this.setHexpand(true);
        this.knownSongs = loadSamples("src/main/resources/samples");


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
            player.mute();
        });
        var muteOffButton = Button.withLabel("Mute Off");
        muteOffButton.onClicked(() -> {
            System.out.println("muteOffButton.onClicked");
            player.unMute();
        });

        List<Button> songButtons = knownSongs.stream().map(sample -> {
            var btnName = sample.title();
            var btn = Button.withLabel(btnName);
            var uri = sample.uri();
            btn.onClicked(() -> {
                System.out.println("Btn: change source to=" + btnName);
                this.player.loadSource(sample.toSongInfo());
            });
            return btn;
        }).toList();

        var testPageContainer = this;
        testPageContainer.append(playButton);
        testPageContainer.append(pauseButton);
        songButtons.forEach(testPageContainer::append);
        testPageContainer.append(muteOnButton);
        testPageContainer.append(muteOffButton);
        testPageContainer.append(volumeFull);
        testPageContainer.append(volumeHalf);
        testPageContainer.append(volumeQuarter);
    }

    public record Sample(
            String id,
            String title,
            URI uri,
            long size,
            String suffix
    ){
        public SongInfo toSongInfo() {
            return new SongInfo(
                    id,
                    title,
                    Optional.of(1),
                    Optional.of(1),
                    Optional.of(192),
                    size,
                    Optional.empty(),
                    "",
                    1L,
                    Optional.empty(),
                    "",
                    "Test artist",
                    "",
                    "Test album",
                    Duration.ofSeconds(121),
                    Optional.empty(),
                    Optional.empty(),
                    suffix,
                    suffix,
                    uri,
                    uri
            );
        }
    }

    private static List<Sample> loadSamples(String dirPath) {
        File dir = new File(dirPath);
        if (!dir.isDirectory()) {
            throw new IllegalStateException("must be a directory with music files: " + dirPath);
        }
        File[] files = dir.listFiles(File::isFile);
        return Stream.of(files).map(File::getAbsoluteFile).map(file -> {
            var id = sha256(file.getAbsolutePath());
            var nameParts = file.getName().split("\\.");
            var suffix = nameParts[nameParts.length - 1];
            var uri = file.toURI();
            var name = file.getName();
            var size = file.length();
            return new Sample(id, name, uri, size, suffix);
        }).toList();
    }


}
