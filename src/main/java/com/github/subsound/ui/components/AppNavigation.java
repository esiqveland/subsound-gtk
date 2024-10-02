package com.github.subsound.ui.components;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AppNavigation {
    private final List<NavigationListener> listeners = new CopyOnWriteArrayList<>();
    private final Navigator navigator;

    public AppNavigation(Navigator navigator) {
        this.navigator = navigator;
    }

    public interface Navigator {
        boolean navigateTo(AppRoute next);
    }

    public sealed interface AppRoute {
        record RouteHome() implements AppRoute {}
        record RouteStarred() implements AppRoute {}
        record SettingsPage() implements AppRoute {}
        record RouteArtistsOverview() implements AppRoute {}
        record RouteAlbumsOverview() implements AppRoute {}
        record RouteArtistInfo(String artistId) implements AppRoute {}
        record RouteAlbumInfo(String albumId) implements AppRoute {}
    }

    public void navigateTo(AppRoute route) {
        boolean ok = this.navigator.navigateTo(route);
        if (!ok) {
            // navigation blocked
            return;
        }
        for (NavigationListener listener : this.listeners) {
            listener.onRouteUpdated(route);
        }
    }

    public interface NavigationListener {
        void onRouteUpdated(AppRoute next);
    }
}
