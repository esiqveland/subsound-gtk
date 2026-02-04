package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.ListStarred;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class StarredLoader extends Box {
    private static final Logger log = LoggerFactory.getLogger(StarredLoader.class);

    private final StarredListView starredView;
    private final AppManager appManager;
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);

    public StarredLoader(
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
        this.onMap(() -> {
            if (!this.isLoaded.compareAndSet(false, true)) {
                return;
            }
            this.appManager.handleAction(new PlayerAction.StarRefresh(false));
        });
        this.starredView = new StarredListView(appManager.getStarredList(), appManager, onNavigate);
        // schedule a refresh of starred when we get shown
        this.onShow(() -> this.appManager.handleAction(new PlayerAction.StarRefresh(true)));
        this.append(this.starredView);
    }

    // enlarge helps making a big fake list to test ListView scroll performance:
    private static List<SongInfo> enlarge(ListStarred listStarred) {
        int size = listStarred.songs().size();
        int newSize = listStarred.songs().size() * 20;
        var newList = new ArrayList<SongInfo>(newSize);
        for (int i = 0; i < newSize; i++) {
            int idx = i % size;
            newList.add(listStarred.songs().get(idx));
        }
        return newList;
    }
}
