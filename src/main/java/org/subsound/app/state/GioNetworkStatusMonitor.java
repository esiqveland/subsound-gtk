package org.subsound.app.state;

import org.gnome.gio.NetworkConnectivity;
import org.gnome.gio.NetworkMonitor;
import org.gnome.glib.GLib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.subsound.app.state.NetworkMonitoring.NetworkStatus.OFFLINE;
import static org.subsound.app.state.NetworkMonitoring.NetworkStatus.ONLINE;

public class GioNetworkStatusMonitor implements NetworkMonitoring {
    private static final Logger log = LoggerFactory.getLogger(GioNetworkStatusMonitor.class);
    private final NetworkMonitor nm;
    private final AtomicReference<NetworkConnectivity> latest = new AtomicReference<>();
    private final AtomicReference<NetworkStatus> override = new AtomicReference<>();
    // use getState() to load latest state
    private final Consumer<Void> listener;

    public GioNetworkStatusMonitor(Consumer<Void> listener) {
        this.listener = listener;
        this.nm = NetworkMonitor.getDefault();
        var connectivity = this.nm.getConnectivity();
        var value = Optional.ofNullable(connectivity).orElse(NetworkConnectivity.FULL);
        log.info("Initial Network connectivity: {} val={}", connectivity, value);
        this.latest.set(value);
        this.nm.onNetworkChanged(ccc -> {
            var prev = this.latest.get();
            var next = this.nm.getConnectivity();
            if (!prev.equals(next)) {
                this.latest.set(next);
                log.info("onNetworkChanged: status changed: prev={} next={}", prev, next);
            }
            this.notifyListeners();
        });
        // In Flatpak, GNetworkMonitorPortal queries D-Bus asynchronously on startup.
        // The initial getConnectivity() call above may return LOCAL before the portal
        // has responded. Schedule a deferred re-check to pick up the real state.
        GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 500, () -> {
            var updated = this.nm.getConnectivity();
            this.latest.set(updated);
            log.info("Deferred network connectivity check: {}", updated);
            this.notifyListeners();
            return GLib.SOURCE_REMOVE;
        });
    }

    private void notifyListeners() {
        if (this.listener != null) {
            this.listener.accept(null);
        }
    }

    public void setOverrideState(Optional<NetworkStatus> a) {
        this.override.set(a.orElse(null));
        this.notifyListeners();
    }

    public NetworkState getState() {
        NetworkStatus overrideStatus = this.override.get();
        if (overrideStatus != null) {
            return new NetworkState(overrideStatus);
        }
        NetworkStatus s = switch (this.latest.get()) {
            case PORTAL, LOCAL -> OFFLINE;
            case FULL, LIMITED -> ONLINE;
            // TODO: implement METERED_ONLINE
            //case LIMITED -> METERED_ONLINE;
        };
        return new NetworkState(s);
    }
}
