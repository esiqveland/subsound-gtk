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
    private final Label statsLabelUsername;
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

        // Top row: server icon + hostname
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

        var onlineIndicatorBox = new Box(Orientation.VERTICAL, 4);
        onlineIndicatorBox.setHalign(Align.CENTER);
        onlineIndicatorBox.setValign(Align.CENTER);
        onlineIndicatorBox.append(statusDot);
        topRow.append(onlineIndicatorBox);
        topRow.append(textBox);

        // Status indicator row - positioned below statusDot on the left
        var statusRow = Box.builder()
                .setOrientation(Orientation.HORIZONTAL)
                .setSpacing(12)
                //.setMarginStart(32) // align with server icon (icon size 24 + spacing 12 = 36, but we use 32 for better alignment)
                .build();

        // Stats row
        this.statsLabelUsername = newStatsLabel();
        this.statsLabelUsername.setCssClasses(Classes.toClassnames(Classes.captionHeading));
        this.statsLabelSongs = newStatsLabel();
        this.statsLabelFolders = newStatsLabel();
        this.statsLabelAppVersion = newStatsLabel();

        this.append(topRow);
        this.append(statusRow);
        this.append(statsLabelUsername);
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
                //.setMarginStart(32) // align with status dot (same as statusRow margin)
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
            var usernameOpt = appManager.getConfig().getServerConfig().map(cfg -> cfg.username());
            Utils.runOnMainThread(() -> applyStatus(new ServerStatus(usernameOpt, false, null)));
        }
    }

    private record ServerStatus(Optional<String> username, boolean online, ServerClient.ServerInfo serverInfo) {}

    private ServerStatus fetchServerStatus() {
        var usernameOpt = appManager.getConfig().getServerConfig().map(cfg -> cfg.username());
        try {
            boolean online = appManager.useClient(ServerClient::testConnection);
            if (online) {
                var info = appManager.useClient(ServerClient::getServerInfo);
                return new ServerStatus(usernameOpt, true, info);
            }
            return new ServerStatus(usernameOpt, false, null);
        } catch (Exception e) {
            log.info("Failed to fetch server status", e);
            return new ServerStatus(usernameOpt, false, null);
        }
    }

    private void applyStatus(ServerStatus status) {
        status.username().ifPresentOrElse(
        username -> statsLabelUsername.setLabel("@%s".formatted(username)),
                () -> statsLabelUsername.setLabel("@")
        );
        statsLabelUsername.setVisible(true);

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
