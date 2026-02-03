package com.github.subsound.ui.views;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.ListPlaylists;
import com.github.subsound.integration.ServerClient.ListStarred;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.components.BoxHolder;
import com.github.subsound.ui.components.FutureLoader;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
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
    private final ThumbnailCache thumbLoader;
    private final Consumer<AppNavigation.AppRoute> onNavigate;

    private final StarredListView2 starredView;
    private final AppManager appManager;
    private final AtomicBoolean isLoaded = new AtomicBoolean(false);

    public StarredLoader(
            ThumbnailCache thumbLoader,
            AppManager appManager,
            Consumer<AppNavigation.AppRoute> onNavigate
    ) {
        super(Orientation.VERTICAL, 0);
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.onNavigate = onNavigate;
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
        this.starredView = new StarredListView2(appManager.getStarredList(), appManager, onNavigate);
        //this.onShow(this::refresh);
        //this.onRealize(this::refresh);
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
