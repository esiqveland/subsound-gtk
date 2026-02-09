package com.github.subsound.app.state;

public interface NetworkMonitoring {
    enum NetworkStatus {
        OFFLINE,
        ONLINE,
        // METERED_ONLINE,
        // LOCAL_MAYBE,
    }
    record NetworkState(
            NetworkStatus status
    ) {}
    NetworkState getState();
}