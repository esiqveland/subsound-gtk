package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.ui.models.GSearchResultItem;
import com.github.subsound.utils.LevenshteinSearch;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class SearchResultStore {
    private static final Logger log = LoggerFactory.getLogger(SearchResultStore.class);

    private final Object lock = new Object();
    private final ListStore<GSearchResultItem> store = new ListStore<>(GSearchResultItem.gtype);
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
                var songs = sortByRelevance(searchResult.songs(), query);
                var sortedResult = new ServerClient.SearchResult(
                        searchResult.artists(),
                        searchResult.albums(),
                        songs
                );
                this.setResults(sortedResult);
                return sortedResult;
            } catch (Exception e) {
                this.currentStatus = SearchStatus.DONE;
                log.info("searchAsync: status={} query={}, id={}", currentStatus, query, id, e);
                throw e;
            }
        });
    }

    public void setResults(ServerClient.SearchResult result) {
        synchronized (lock) {
            var artistItems = result.artists().stream()
                    .map(GSearchResultItem::ofArtist);
            var albumItems = result.albums().stream()
                    .map(GSearchResultItem::ofAlbum);
            var songItems = result.songs().stream()
                    .map(GSearchResultItem::ofSong);
            var items = Stream.concat(Stream.concat(artistItems, albumItems), songItems)
                    .toArray(GSearchResultItem[]::new);

            Utils.runOnMainThreadFuture(() -> {
                store.removeAll();
                store.splice(0, 0, items);
            }).join();

            log.info("setResults: albums={} songs={}", result.albums().size(), result.songs().size());
        }
    }

    public CompletableFuture<Void> clear() {
        return Utils.runOnMainThreadFuture(() -> store.removeAll());
    }

    public ListStore<GSearchResultItem> getStore() {
        return store;
    }

    static int scoreTitle(String title, String query) {
        var lowerTitle = title.toLowerCase();
        var lowerQuery = query.toLowerCase();
        for (int len = lowerQuery.length(); len > 0; len--) {
            for (int start = 0; start + len <= lowerQuery.length(); start++) {
                if (lowerTitle.contains(lowerQuery.substring(start, start + len))) {
                    return len;
                }
            }
        }
        // TODO: ok, it does not make all that much sense to distance the entire title against the query,
        // but at least its a little better than the default result ranking
        return LevenshteinSearch.DamerauLevenshtein.calculateDistance(lowerTitle, lowerQuery);
    }

    static List<ServerClient.SongInfo> sortByRelevance(List<ServerClient.SongInfo> songs, String query) {
        var lowerQuery = query.toLowerCase();
        return songs.stream()
                .sorted(Comparator
                        .comparingInt((ServerClient.SongInfo s) -> scoreTitle(s.title(), query)).reversed()
                        .thenComparing((ServerClient.SongInfo s) -> !s.title().toLowerCase().startsWith(lowerQuery))
                )
                .toList();
    }
}
