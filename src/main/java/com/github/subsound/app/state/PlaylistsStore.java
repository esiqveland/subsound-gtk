package com.github.subsound.app.state;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.PlaylistKind;
import com.github.subsound.integration.ServerClient.PlaylistSimple;
import com.github.subsound.utils.Utils;
import org.gnome.glib.Type;
import org.gnome.gio.ListStore;
import org.gnome.gobject.GObject;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.slf4j.Logger;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

public class PlaylistsStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PlaylistsStore.class);

    public static final String STARRED_ID = "starred";
    public static final String DOWNLOADED_ID = "downloaded";

    private final AppManager appManager;
    // metaStore stores a list of playlist metadata, for listing all playlists that exist
    private final ListStore<GPlaylist> metaStore = new ListStore<>(GPlaylist.gtype);
    private final ArrayList<String> backingIds = new ArrayList<>();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final Object lock = new Object();
    private GPlaylist starredPlaylist;

    public PlaylistsStore(AppManager appManager) {
        this.appManager = appManager;
    }

    public CompletableFuture<Void> refreshListAsync() {
        return Utils.doAsync(() -> {
            if (!isLoading.compareAndSet(false, true)) {
                log.info("Ignoring refresh request while loading");
                return null;
            }

            try {
                var task1 = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getPlaylists));
                var task2 = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getStarred));
                var serverPlaylists = task1.join();
                var starredList = task2.join();

                synchronized (lock) {
                    // Build the full playlist list with synthetic entries first
                    var allPlaylists = buildPlaylistList(serverPlaylists.playlists(), starredList.songs().size());
                    var diff = mergeRefresh(backingIds, allPlaylists);

                    var removalIndices = diff.removalIndices();
                    var insertions = diff.insertions();

                    // Build lookup map for updating existing items
                    var playlistById = new HashMap<String, PlaylistSimple>(allPlaylists.size());
                    for (var p : allPlaylists) {
                        playlistById.put(p.id(), p);
                    }

                    // Apply mutations on main thread
                    Utils.runOnMainThreadFuture(() -> {
                        // Update existing items with fresh data before removals
                        for (int i = 0; i < metaStore.getNItems(); i++) {
                            var gPlaylist = metaStore.getItem(i);
                            var fresh = playlistById.get(gPlaylist.getId());
                            if (fresh != null) {
                                gPlaylist.setValue(fresh);
                            }
                        }

                        // Remove backwards to preserve indices
                        for (int i = removalIndices.size() - 1; i >= 0; i--) {
                            metaStore.removeAt(removalIndices.get(i));
                        }
                        // Insert forwards — positions account for previous insertions
                        for (var ins : insertions) {
                            metaStore.insert(ins.position(), ins.item());
                        }
                    }).join();

                    // Capture starred playlist reference on first load
                    if (starredPlaylist == null && metaStore.getNItems() > 0) {
                        starredPlaylist = metaStore.getItem(0);
                    }

                    // Update backing state
                    backingIds.clear();
                    backingIds.ensureCapacity(allPlaylists.size());
                    for (var p : allPlaylists) {
                        backingIds.add(p.id());
                    }
                    log.info("mergeRefresh: total={} removals={} insertions={}",
                            allPlaylists.size(),
                            removalIndices.size(),
                            insertions.size()
                    );

                    return null;
                }
            } finally {
                isLoading.set(false);
            }
        });
    }

    private List<PlaylistSimple> buildPlaylistList(List<PlaylistSimple> serverPlaylists, int starredCount) {
        var downloadCount = this.appManager.getDownloadQueue().size();

        var starred = new PlaylistSimple(
                STARRED_ID,
                "Starred",
                PlaylistKind.STARRED,
                Optional.empty(),
                starredCount,
                Instant.now()
        );
        var downloaded = new PlaylistSimple(
                DOWNLOADED_ID,
                "Downloaded",
                PlaylistKind.DOWNLOADED,
                Optional.empty(),
                downloadCount,
                Instant.now()
        );

        var result = new ArrayList<PlaylistSimple>(serverPlaylists.size() + 2);
        result.add(starred);
        result.add(downloaded);
        result.addAll(serverPlaylists);
        return result;
    }

    record Insertion(int position, GPlaylist item) {}
    record Differences(
            ArrayList<Integer> removalIndices,
            ArrayList<Insertion> insertions
    ) {}
    private static Differences mergeRefresh(ArrayList<String> backingIds, List<PlaylistSimple> newPlaylists) {
        var newIds = newPlaylists.stream().map(PlaylistSimple::id).toList();
        var indexDiff = StarredListStore.computeDiff(backingIds, newIds);

        final Map<String, GPlaylist> resolved = new HashMap<>(newPlaylists.size());
        for (var p : newPlaylists) {
            resolved.put(p.id(), GPlaylist.newInstance(p));
        }

        var insertions = new ArrayList<Insertion>();
        for (var ins : indexDiff.insertions()) {
            insertions.add(new Insertion(ins.position(), resolved.get(newIds.get(ins.position()))));
        }

        return new Differences(indexDiff.removalIndices(), insertions);
    }

    public void updateStarredCount(int count) {
        var sp = this.starredPlaylist;
        if (sp == null) {
            return;
        }
        var old = sp.getPlaylist();
        Utils.runOnMainThread(() -> sp.setValue(new PlaylistSimple(
                old.id(), old.name(), old.kind(), old.coverArtId(), count, old.created()
        )));
    }

    public ListStore<GPlaylist> playlistsListStore() {
        return this.metaStore;
    }

    public static class GPlaylist extends GObject {
        public static final Type gtype = Types.register(GPlaylist.class);

        private String id;
        private PlaylistSimple value;

        public GPlaylist(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        @Property
        public String getId() {
            return id;
        }
        @Property
        public String getName() {
            return value.name();
        }
        public PlaylistSimple getPlaylist() {
            return value;
        }

        public void setValue(PlaylistSimple value) {
            this.value = value;
            this.notify("name");
        }

        public static GPlaylist newInstance(PlaylistSimple value) {
            GPlaylist obj = GObject.newInstance(gtype);
            obj.id = value.id();
            obj.value = value;
            return obj;
        }
    }
}
