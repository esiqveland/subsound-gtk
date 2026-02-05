package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.PlaylistsListView;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PlaylistsViewLoader extends Box {
    private static final Logger log = LoggerFactory.getLogger(PlaylistsViewLoader.class);
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);
    private final AppManager appManager;
    private final PlaylistsListView playlistsListView;

    public PlaylistsViewLoader(
            ThumbnailCache thumbLoader,
            AppManager appManager,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(Align.FILL);
        this.setValign(Align.FILL);

        this.playlistsListView = new PlaylistsListView(appManager);
        this.playlistsListView.setHexpand(true);
        this.playlistsListView.setVexpand(true);
        this.playlistsListView.setHalign(Align.FILL);
        this.playlistsListView.setValign(Align.FILL);

        var signal = this.onMap(() -> {
            // Initial load is done by AppManager when client is set
            // But we can trigger a refresh here if needed
        });
        this.onShow(() -> {
            this.appManager.handleAction(new PlayerAction.StarRefresh())
        })

        this.onDestroy(() -> signal.disconnect());

        this.append(playlistsListView);
    }
}