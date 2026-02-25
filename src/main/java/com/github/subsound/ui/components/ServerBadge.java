package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.NetworkMonitoring.NetworkState;
import com.github.subsound.app.state.NetworkMonitoring.NetworkStatus;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.utils.Utils;
import org.gnome.adw.ActionRow;
import org.gnome.adw.ButtonRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.SwitchRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.SelectionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.subsound.ui.components.Classes.boxedList;

public class ServerBadge extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(ServerBadge.class);

    private final AppManager appManager;
    private final ActionRow serverRow;
    private final ActionRow statsRow;
    private final Label statusDot;
    private final SwitchRow offlineSwitch;
    private final ButtonRow syncButton;
    private final ButtonRow configureServerButton;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pingTask;
    private volatile NetworkStatus currentNetworkStatus = NetworkStatus.ONLINE;
    private final AtomicBoolean updatingSwitch = new AtomicBoolean(false);

    public ServerBadge(AppManager appManager) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;

        this.setMarginTop(8);
        this.setMarginBottom(8);
        this.setMarginStart(8);
        this.setMarginEnd(8);

        // Status indicator shown as suffix on the server row
        this.statusDot = Label.builder()
                .setLabel("\u25CF") // ●
                .setValign(Align.CENTER)
                .setTooltipText("Checking…")
                .build();
        this.statusDot.addCssClass(Classes.labelDim.className());

        // Server hostname + username row
        this.serverRow = ActionRow.builder()
                .setTitle(getServerHostNameOrNotConnected())
                .setSubtitle("Checking connectivity…")
                .setIconName(Icons.NetworkServer.getIconName())
                .setUseMarkup(false)
                .build();
        this.serverRow.setSubtitleLines(1);
        this.serverRow.setTitleLines(1);
        this.serverRow.addSuffix(statusDot);

        // Songs / folders / version — hidden until server info arrives
        this.statsRow = ActionRow.builder()
                .setTitle("")
                .setSubtitle("")
                .setUseMarkup(false)
                .setVisible(false)
                .build();

        // Offline mode toggle
        this.offlineSwitch = SwitchRow.builder()
                .setTitle("Offline mode")
                .setSubtitle("Use only cached data")
                .build();
        this.offlineSwitch.onNotify("active", _ -> {
            if (updatingSwitch.get()) return;
            if (offlineSwitch.getActive()) {
                appManager.handleAction(new PlayerAction.OverrideNetworkStatus(Optional.of(NetworkStatus.OFFLINE)));
            } else {
                appManager.handleAction(new PlayerAction.OverrideNetworkStatus(Optional.empty()));
            }
        });

        // Sync library action
        this.syncButton = ButtonRow.builder()
                .setTitle("Sync library")
                .setStartIconName("view-refresh-symbolic")
                .setTooltipText("Sync metadata for offline use")
                .build();
        this.syncButton.onActivated(() -> {
            syncButton.setSensitive(false);
            appManager.handleAction(new PlayerAction.SyncDatabase()).whenComplete((v, err) ->
                    Utils.runOnMainThread(() -> syncButton.setSensitive(currentNetworkStatus != NetworkStatus.OFFLINE))
            );
        });

        this.configureServerButton = ButtonRow.builder()
                .setTitle("Configure server")
                .build();
        this.configureServerButton.onActivated(() -> {
            appManager.navigateTo(new AppNavigation.AppRoute.SettingsPage());
        });


        var list = ListBox.builder()
                .setSelectionMode(SelectionMode.NONE)
                .setCssClasses(boxedList.add())
                .build();
        list.append(serverRow);
        list.append(statsRow);
        list.append(offlineSwitch);
        list.append(syncButton);
        list.append(configureServerButton);

        var clamp = new Clamp();
        clamp.setMaximumSize(240);
        clamp.setHalign(Align.FILL);
        clamp.setValign(Align.FILL);
        clamp.setChild(list);
        this.append(clamp);

        this.pingTask = scheduler.scheduleWithFixedDelay(this::checkConnectivity, 0, 30, TimeUnit.SECONDS);

        this.appManager.addOnStateChanged(this);
        updateNetworkStatus(this.appManager.getState().networkState());

        this.onDestroy(() -> {
            this.appManager.removeOnStateChanged(this);
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            scheduler.shutdown();
        });
    }

    /**
     * Called when the popover is shown to refresh hostname and stats.
     */
    public void refresh() {
        serverRow.setTitle(getServerHostNameOrNotConnected());
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
        serverRow.setSubtitle(status.username().map("@%s"::formatted).orElse(""));

        if (status.online()) {
            statusDot.removeCssClass("error");
            statusDot.removeCssClass(Classes.labelDim.className());
            statusDot.addCssClass("success");
            statusDot.setTooltipText("Online");

            if (status.serverInfo() != null) {
                var info = status.serverInfo();
                var folders = info.folderCount().map(" · %d folders"::formatted).orElse("");
                statsRow.setTitle("%,d songs%s".formatted(info.songCount(), folders));
                statsRow.setSubtitle(
                        info.serverVersion().map("v%s"::formatted)
                                .orElse("API v" + info.apiVersion())
                );
                statsRow.setVisible(true);
            }
        } else {
            statusDot.removeCssClass("success");
            statusDot.removeCssClass(Classes.labelDim.className());
            statusDot.addCssClass("error");
            statusDot.setTooltipText("Offline");
            statsRow.setVisible(false);
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

    public void shutdown() {
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        scheduler.shutdown();
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var networkState = state.networkState();
        if (networkState.status() != this.currentNetworkStatus) {
            this.currentNetworkStatus = networkState.status();
            Utils.runOnMainThread(() -> updateNetworkStatus(networkState));
        }
    }

    private void updateNetworkStatus(NetworkState networkState) {
        updatingSwitch.set(true);
        try {
            boolean offline = networkState.status() == NetworkStatus.OFFLINE;
            offlineSwitch.setActive(offline);
            if (offline) {
                syncButton.setSensitive(false);
                syncButton.setTooltipText("Must be online to sync library");
            } else {
                syncButton.setSensitive(true);
                syncButton.setTooltipText("Sync library to enable offline mode");
            }
        } finally {
            updatingSwitch.set(false);
        }
    }
}