package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.IconSize;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;

import org.gnome.gtk.Orientation;
import org.gnome.pango.EllipsizeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ServerBadge extends Box {
    private static final Logger log = LoggerFactory.getLogger(ServerBadge.class);

    private final AppManager appManager;
    private final Label hostnameLabel;
    private final Label statsLabelSongs;
    private final Label statsLabelFolders;
    private final Label statsLabelAppVersion;
    private final Label statusDot;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pingTask;

    public ServerBadge(AppManager appManager) {
        super(Orientation.VERTICAL, 4);
        this.appManager = appManager;

        this.setMarginTop(8);
        this.setMarginBottom(8);
        this.setMarginStart(12);
        this.setMarginEnd(12);

        // Top row: server icon + hostname + status indicator
        var topRow = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setSpacing(12)
                .build();

        var serverIcon = Image.fromIconName(Icons.NetworkServer.getIconName());
        serverIcon.setIconSize(IconSize.LARGE);

        this.hostnameLabel = Label.builder()
                .setLabel(getServerHostNameOrNotConnected())
                .setHalign(Align.START)
                .setHexpand(true)
                .setEllipsize(EllipsizeMode.END)
                .build();

        this.statusDot = Label.builder()
                .setLabel("\u25CF") // ●
                .setValign(Align.CENTER)
                .build();
        this.statusDot.setTooltipText("Checking...");
        this.statusDot.addCssClass("dim-label");

        var textBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(2)
                .setHexpand(true)
                .build();

        var titleLabel = Label.builder()
                .setLabel("Connected to")
                .setHalign(Align.START)
                .build();
        titleLabel.addCssClass("dim-label");
        titleLabel.addCssClass("caption");

        textBox.append(titleLabel);
        textBox.append(hostnameLabel);

        topRow.append(serverIcon);
        topRow.append(textBox);
        topRow.append(statusDot);

        // Stats row
        this.statsLabelSongs = newStatsLabel();
        this.statsLabelFolders = newStatsLabel();
        this.statsLabelAppVersion = newStatsLabel();

        this.append(topRow);
        this.append(statsLabelSongs);
        this.append(statsLabelFolders);
        this.append(statsLabelAppVersion);

        // Start periodic ping
        this.pingTask = scheduler.scheduleWithFixedDelay(this::checkConnectivity, 0, 30, TimeUnit.SECONDS);

        this.onDestroy(() -> {
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            scheduler.shutdown();
        });
    }

    private Label newStatsLabel() {
        return Label.builder()
                .setLabel("")
                .setHalign(Align.START)
                .setMarginStart(44) // align with text (icon 24 + spacing 12 + a bit)
                .setCssClasses(Classes.toClassnames("dim-label", "caption"))
                .setVisible(false)
                .build();
    }

    /**
     * Called when the popover is shown to refresh hostname and stats.
     */
    public void refresh() {
        hostnameLabel.setLabel(getServerHostNameOrNotConnected());
        Utils.doAsync(this::fetchServerStatus).thenAccept(status ->
                Utils.runOnMainThread(() -> applyStatus(status))
        );
    }

    private void checkConnectivity() {
        try {
            var status = fetchServerStatus();
            Utils.runOnMainThread(() -> applyStatus(status));
        } catch (Exception e) {
            log.info("Connectivity check failed", e);
            Utils.runOnMainThread(() -> applyStatus(new ServerStatus(false, null)));
        }
    }

    private record ServerStatus(boolean online, ServerClient.ServerInfo serverInfo) {}

    private ServerStatus fetchServerStatus() {
        try {
            boolean online = appManager.useClient(ServerClient::testConnection);
            if (online) {
                var info = appManager.useClient(ServerClient::getServerInfo);
                return new ServerStatus(true, info);
            }
            return new ServerStatus(false, null);
        } catch (Exception e) {
            log.info("Failed to fetch server status", e);
            return new ServerStatus(false, null);
        }
    }

    private void applyStatus(ServerStatus status) {
        if (status.online()) {
            statusDot.removeCssClass("error");
            statusDot.removeCssClass("dim-label");
            statusDot.addCssClass("success");
            statusDot.setTooltipText("Online");
            if (status.serverInfo() != null) {
                var info = status.serverInfo();
                var text = "%d songs".formatted(info.songCount());
                statsLabelSongs.setLabel(text);
                statsLabelSongs.setVisible(true);
                info.folderCount().ifPresentOrElse(folderCount -> {
                        statsLabelFolders.setLabel("%d folders".formatted(folderCount));
                        statsLabelFolders.setVisible(true);
                    },
                    () -> statsLabelFolders.setVisible(false)
                );
                info.serverVersion().or(() -> Optional.ofNullable(info.apiVersion())).ifPresentOrElse(serverVersion -> {
                        statsLabelAppVersion.setLabel(serverVersion);
                        statsLabelAppVersion.setVisible(true);
                    },
                    () -> statsLabelAppVersion.setVisible(false)
                );
                //var text = "API v%s \u00b7 %,d songs".formatted(info.apiVersion(), info.songCount());

            }

        } else {
            statusDot.removeCssClass("success");
            statusDot.removeCssClass("dim-label");
            statusDot.addCssClass("error");
            statusDot.setTooltipText("Offline");
            statsLabelSongs.setVisible(false);
            statsLabelFolders.setVisible(false);
            statsLabelAppVersion.setVisible(false);
        }
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
}
