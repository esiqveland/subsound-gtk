package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SearchResultStore {
    private static final Logger log = LoggerFactory.getLogger(SearchResultStore.class);

    private final Object lock = new Object();
    private final ListStore<GSongInfo> store = new ListStore<>(GSongInfo.gtype);
    private final Supplier<ServerClient> client;

    public SearchResultStore(Supplier<ServerClient> client) {
        this.client = client;
    }

    public void search(String query) {
        String id = UUID.randomUUID().toString();
        this.client.get().search(query);
    }

    public void setResults(List<SongInfo> songs) {
        synchronized (lock) {
            var items = songs.stream()
                    .map(GSongInfo::newInstance)
                    .toArray(GSongInfo[]::new);

            Utils.runOnMainThreadFuture(() -> {
                store.removeAll();
                store.splice(0, 0, items);
            }).join();

            log.info("setResults: count={}", songs.size());
        }
    }

    public void clear() {
        synchronized (lock) {
            Utils.runOnMainThreadFuture(() -> store.removeAll()).join();
        }
    }

    public ListStore<GSongInfo> getStore() {
        return store;
    }
}
