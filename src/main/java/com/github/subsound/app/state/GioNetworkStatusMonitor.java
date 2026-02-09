package com.github.subsound.app.state;

import org.gnome.gio.NetworkConnectivity;
import org.gnome.gio.NetworkMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.github.subsound.app.state.NetworkMonitoring.NetworkStatus.OFFLINE;
import static com.github.subsound.app.state.NetworkMonitoring.NetworkStatus.ONLINE;

public class GioNetworkStatusMonitor implements NetworkMonitoring {
    private static final Logger log = LoggerFactory.getLogger(GioNetworkStatusMonitor.class);
    private final NetworkMonitor nm;
    private final AtomicReference<NetworkConnectivity> latest = new AtomicReference<>();
    // use getState() to load latest state
    private final Consumer<Void> listener;

    public GioNetworkStatusMonitor(Consumer<Void> listener) {
        this.listener = listener;
        this.nm = NetworkMonitor.getDefault();
        var connectivity = this.nm.getConnectivity();
        this.latest.set(connectivity);
        this.nm.onNetworkChanged(ccc -> {
            var prev = this.latest.get();
            var next = this.nm.getConnectivity();
            if (!prev.equals(next)) {
                this.latest.set(next);
                log.info("Network status changed: prev={} next={}", prev, next);
            }
            if (this.listener != null) {
                this.listener.accept(null);
            }
        });
    }

    public NetworkState getState() {
        NetworkStatus s = switch (this.latest.get()) {
            case PORTAL, LOCAL -> OFFLINE;
            case FULL, LIMITED -> ONLINE;
            // TODO: implement METERED_ONLINE
            //case LIMITED -> METERED_ONLINE;
        };
        return new NetworkState(s);
    }
}
