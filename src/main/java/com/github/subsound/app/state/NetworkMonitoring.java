package com.github.subsound.app.state;

import java.util.Optional;

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
    void setOverrideState(Optional<NetworkStatus> a);
}