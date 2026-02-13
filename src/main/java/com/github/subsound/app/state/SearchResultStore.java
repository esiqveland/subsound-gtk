package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class SearchResultStore {
    private static final Logger log = LoggerFactory.getLogger(SearchResultStore.class);

    private final Object lock = new Object();
    private final ListStore<GSongInfo> store = new ListStore<>(GSongInfo.gtype);
    private final Supplier<ServerClient> client;

    private volatile SearchStatus currentStatus = SearchStatus.INITIAL;
    private final AtomicReference<String> searchId = new AtomicReference<>();

    public SearchResultStore(Supplier<ServerClient> client) {
        this.client = client;
    }

    public enum SearchStatus {
        INITIAL,
        LOADING,
        DONE,
    }

    public SearchState getState() {
        return new SearchState(this.currentStatus);
    }

    public CompletableFuture<ServerClient.SearchResult> searchAsync(String query) {
        String id = UUID.randomUUID().toString();
        this.currentStatus = SearchStatus.LOADING;
        log.info("searchAsync: status={} query={}, id={}", currentStatus, query, id);
        this.searchId.set(id);

        return Utils.doAsync(() -> {
            this.clear().join();
            try {
                var searchResult = this.client.get().search(query);
                if (!id.equals(this.searchId.get())) {
                    // another search has already started, ignore this one and do not touch currentStatus
                    return searchResult;
                }
                log.info("searchAsync: status={} query={}, id={} songs={}", currentStatus, query, id, searchResult.songs().size());
                this.setResults(searchResult);
                return searchResult;
            } catch (Exception e) {
                this.currentStatus = SearchStatus.DONE;
                log.info("searchAsync: status={} query={}, id={}", currentStatus, query, id, e);
                throw e;
            }
        });
    }

    public void setResults(ServerClient.SearchResult result) {
        synchronized (lock) {
            var songs = result.songs();
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

    public CompletableFuture<Void> clear() {
        return Utils.runOnMainThreadFuture(() -> store.removeAll());
    }

    public ListStore<GSongInfo> getStore() {
        return store;
    }
}
