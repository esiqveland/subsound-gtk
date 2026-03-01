package org.subsound;

import org.subsound.app.state.AppManager;
import org.subsound.configuration.Config;
import org.subsound.integration.ServerClient;
import org.subsound.integration.platform.mpriscontroller.MPrisController;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.utils.LogUtils;
import org.subsound.utils.Utils;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.Application;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.ApplicationFlags;
import org.javagi.base.Out;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    private final static Logger log = LoggerFactory.getLogger(Main.class);
    private final static String rootLogLevel = Optional.ofNullable(System.getenv("JAVA_LOG_LEVEL")).orElse("INFO");
    static {
        LogUtils.setRootLogLevel(rootLogLevel);
        // Bridge/route all JUL log records to the SLF4J API.
        SLF4JBridgeHandler.install();
    }

    private final Config config;
    private final AppManager appManager;
    private final MPrisController mprisController;

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(new String[]{}));
        Pixbuf.getFormats().forEach(pixbufFormat -> {
            log.info("pixbufFormat supported: %s %s".formatted(pixbufFormat.getName(), pixbufFormat.getDescription()));
        });

        this.config = Config.createDefault();
        var thumbnailCache = new ThumbnailCache(config.dataDir);
        var client = Optional.ofNullable(config.serverConfig).map(ServerClient::create);
        var player = new PlaybinPlayer();
        this.appManager = new AppManager(this.config, player, thumbnailCache, client);
        this.mprisController = new MPrisController(appManager);
        Utils.doAsync(() -> {
            try {
                this.mprisController.run();
            } catch (Throwable throwable) {
                log.warn("error starting mprisController: ", throwable);
            }
        });

        try {
            var mainAppRef = new AtomicReference<MainApplication>();
            var app = new Application("org.subsound.Subsound", ApplicationFlags.DEFAULT_FLAGS);
            app.onActivate(() -> {
                MainApplication mainApp = new MainApplication(appManager);
                mainAppRef.set(mainApp);
                mainApp.runActivate(app);
            });
            app.onShutdown(() -> {
                var mainApp = mainAppRef.get();
                if (mainApp != null) {
                    // Save window size before shutdown
                    var size = mainApp.getLastWindowSize();
                    if (size != null) {
                        this.config.windowWidth = size.width();
                        this.config.windowHeight = size.height();
                        try {
                            config.saveToFile();
                        } catch (Exception e) {
                            log.warn("Failed to save config", e);
                        }
                    }
                    mainApp.shutdown();
                }
                mprisController.stop();
                player.quit();
                appManager.shutdown();
            });
            app.run(args);
        } finally {
            player.quit();
        }
    }

    public static void main(String[] args) {
        new Main(args);
    }
}

