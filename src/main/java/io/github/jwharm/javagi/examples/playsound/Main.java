package io.github.jwharm.javagi.examples.playsound;

import io.github.jwharm.javagi.base.Out;
import io.github.jwharm.javagi.examples.playsound.app.state.AppManager;
import io.github.jwharm.javagi.examples.playsound.configuration.Config;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.Application;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.ApplicationFlags;
import org.slf4j.bridge.SLF4JBridgeHandler;

public class Main {
    static {
        // Bridge/route all JUL log records to the SLF4J API.
        SLF4JBridgeHandler.install();
    }

    private final Config config;
    private final AppManager appManager;

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(new String[]{}));
        Pixbuf.getFormats().forEach(pixbufFormat -> {
            System.out.println("pixbufFormat: %s %s".formatted(pixbufFormat.getName(), pixbufFormat.getDescription()));
        });

        this.config = Config.createDefault();
        var songCache = new SongCache(config.cacheDir);
        var thumbnailCache = new ThumbnailCache(config.cacheDir);
        var client = ServerClient.create(config.serverConfig);
        var player = new PlaybinPlayer();
        this.appManager = new AppManager(this.config, player, songCache, thumbnailCache, client);

        try {
            var app = new Application("com.subsound.player", ApplicationFlags.DEFAULT_FLAGS);
            app.onActivate(() -> {
                MainApplication mainApp = new MainApplication(appManager);
                mainApp.runActivate(app);
            });
            app.onShutdown(player::quit);
            app.run(args);
        } finally {
            player.quit();
        }
    }

    public static void main(String[] args) {
        new Main(args);
    }
}

