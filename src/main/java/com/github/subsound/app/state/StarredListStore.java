package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class StarredListStore {
    private static final Logger log = LoggerFactory.getLogger(StarredListStore.class);

    private final Object lock = new Object();
    private final ListStore<GSongInfo> store = new ListStore<>(GSongInfo.gtype);
    private final AppManager appManager;
    private final ConcurrentHashMap<String, GSongInfo> deduped = new ConcurrentHashMap<>();
    private AtomicBoolean isLoading = new AtomicBoolean(false);

    public StarredListStore(AppManager appManager) {
        this.appManager = appManager;
    }

    public void handleStarred(PlayerAction.Star2 star) {}
    public void handleStarred(PlayerAction.Star star) {}
    public void handleRefresh(PlayerAction.StarRefresh refresh) {
        if (isLoading.get()) {
            log.info("Ignoring refresh request while loading");
            return;
        }
        refreshAsync();
    }

    public boolean getIsLoading() {
        return isLoading.get();
    }

    public CompletableFuture<Void> refreshAsync() {
        return Utils.doAsync(() -> {
            if (!isLoading.compareAndSet(false, true)) {
                // we are already loading
                return null;
            }

            try {
                var list = this.appManager.useClient(ServerClient::getStarred);
                synchronized (lock) {
                    var songs = list.songs();
                    var items = new GSongInfo[songs.size()];
                    int idx = 0;
                    for (var song : songs) {
                        var gSong = deduped.computeIfAbsent(song.id(), key -> GSongInfo.newInstance(song));
                        // TODO: replace gSong.songInfo with new underlying data
                        items[idx] = gSong;
                        idx++;
                    }
                    Utils.runOnMainThreadFuture(() -> {
                        store.removeAll();
                        store.splice(0, 0, items);

                    }).join();
                    return null;
                }
            } finally {
                isLoading.set(false);
            }
        });
    }

    public void addStarred(SongInfo songInfo) {
        // TODO: Consider storing the list in reverse order, and displaying it reversed order:
        //  appending to 0th element could be slow when reaching 10k+ songs:
        var song = deduped.computeIfAbsent(songInfo.id(), key -> GSongInfo.newInstance(songInfo));
        synchronized (lock) {
            song.setStarredAt(song.getSongInfo().starred().or(() -> Optional.of(Instant.now())));
            Utils.runOnMainThreadFuture(() -> store.insert(0, song)).join();
        }
    }

    public void removeStarred(SongInfo a) {
        synchronized (lock) {
            var it = this.store.iterator();
            int idx = 0;
            var indices = new ArrayList<Integer>();
            while (it.hasNext()) {
                var s = it.next();
                if (s.getSongInfo() == a || s.getSongInfo().id().equals(a.id())) {
                    s.setStarredAt(Optional.empty());
                    indices.add(idx);
                }
                idx++;
            }

//            Utils.runOnMainThread(() -> {
            int count = 0;
            for (int pos : indices) {
                // when removing multiple items, we need to adjust for shift in indices:
                int adjustedIndex = pos - count;
                this.store.removeAt(adjustedIndex);
                count++;
            }
            log.info("removed songId={} from {} positions", a.id(), count);
//            });
        }
    }

    public ListStore<GSongInfo> getStore() {
        return store;
    }

}
