package org.subsound.app.state;

import org.subsound.app.state.PlayerAction.PlayMode;
import org.subsound.integration.ServerClient.ObjectIdentifier;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.sound.GstPlaybinPlayer;
import org.subsound.sound.Player;
import org.subsound.ui.models.GQueueItem;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.ui.views.PlaylistListViewV2;
import org.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

// PlayQueue:
// When a user adds a song to the playqueue, it should be prioritized higher than the automatically queued songs.
// so when its added to the end, it should be added to the end of user added songs, or if no such songs exist,
// it should be added as the next song to play.
public class PlayQueue implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PlayQueue.class);

    private final Object lock = new Object();
    private final Player player;
    private final Consumer<PlayQueueState> onStateChanged;
    private final Consumer<GSongInfo> onPlay;
    private final ListStore<GQueueItem> listStore = new ListStore<>(GQueueItem.gtype);
    private final GSongStore songstore;
    private Optional<ObjectIdentifier> playContext = Optional.empty();
    private Optional<Integer> position = Optional.empty();
    // Stored explicitly (not derived from listStore) so it is correct synchronously on a
    // play transition, before the main-thread listStore rebuild has landed. This lets
    // AppManager snapshot a consistent queue state eagerly when a song switch starts.
    private Optional<String> playingItemId = Optional.empty();
    private PlayMode playMode = PlayMode.NORMAL;
    // Advance the queue on the end-of-stream edge event. Reacting to state == END_OF_STREAM
    // in a state listener instead would re-fire on unrelated notifications (volume changes,
    // setSource's own notify) while the player lingers in that state, double-skipping songs.
    private final GstPlaybinPlayer.OnStreamEnded streamEndedListener = this::onStreamEnded;

    public PlayQueue(
            Player player,
            GSongStore songStore,
            Consumer<PlayQueueState> onStateChanged,
            Consumer<GSongInfo> onPlay
    ) {
        this.player = player;
        this.songstore = songStore;
        this.onStateChanged = onStateChanged;
        this.onPlay = onPlay;
        this.player.onStreamEnded(this.streamEndedListener);
    }

    public ListStore<GQueueItem> getListStore() {
        return this.listStore;
    }

    public PlayQueueState getState() {
        synchronized (lock) {
            return new PlayQueueState(this.playContext, position, playingItemId, playMode);
        }
    }

    /** Must be called while holding {@code lock}. */
    private Optional<String> queueItemIdAt(Optional<Integer> pos) {
        return pos
                .filter(p -> p >= 0 && p < listStore.getNItems())
                .map(p -> listStore.getItem(p).getQueueItemId());
    }

    public void playPosition(int newPosition) {
        synchronized (lock) {
            if (newPosition < 0) {
                log.warn("playPosition: can not play invalid position={}", newPosition);
                return;
            }
            if (newPosition >= listStore.getNItems()) {
                log.warn("playPosition: can not play invalid position={}", newPosition);
                return;
            }
            var newItem = listStore.get(newPosition);
            int oldPosition = this.position.orElse(-1);
            this.position = Optional.of(newPosition);
            this.playingItemId = Optional.ofNullable(newItem.getQueueItemId());
            updateCurrentItemStyling(oldPosition, newPosition);
            this.onPlay.accept(newItem.getSongInfo());
            this.notifyState();
        }
    }

    /**
     * Restore queue from persisted state. Does NOT trigger playback.
     * Must be called on the main thread before the GLib main loop starts.
     */
    public void restoreQueue(
            List<GQueueItem> items,
            Optional<Integer> position,
            PlayMode playMode,
            Optional<ObjectIdentifier> playContext
    ) {
        synchronized (lock) {
            this.playContext = playContext;
            this.playMode = playMode;
            this.position = position.filter(p -> p >= 0 && p < items.size());

            this.listStore.removeAll();
            this.listStore.splice(0, 0, items.toArray(GQueueItem[]::new));
            var pos = this.position.filter(p -> p >= 0 && p < listStore.getNItems());
            this.playingItemId = queueItemIdAt(pos);
            if (pos.isPresent()) {
                listStore.getItem(pos.get()).getSongInfo().setIsPlaying(true);
            }

            this.notifyState();
        }
    }

    public CompletableFuture<Void> playAndReplaceQueue(PlayerAction.PlayAndReplaceQueue a) {
        return Utils.doAsync(() -> {
            int targetPos = a.position();
            boolean targetValid = targetPos >= 0 && targetPos < a.queue().size();
            // Commit the new play identity (position + queueItemId) before the listStore
            // rebuild, so the eager state snapshot taken in onPlay (AppManager.loadSourceAsync)
            // already carries the new playingItemId and the UI highlight moves immediately.
            int oldPos;
            synchronized (lock) {
                this.playContext = Optional.ofNullable(a.playContext());
                oldPos = this.position.orElse(-1);
                if (targetValid) {
                    this.position = Optional.of(targetPos);
                    this.playingItemId = Optional.ofNullable(a.queue().get(targetPos).id());
                }
            }

            if (targetValid) {
                var targetSong = songstore.newInstance(a.queue().get(targetPos).song());
                // Move the shared isPlaying flag here: replaceQueueSlots derives its "old"
                // position from this.position, which was just overwritten above, so it can
                // no longer clear the previously playing song. GSongInfo instances are
                // interned per song id, so signal-driven rows (e.g. AlbumInfoPage) also
                // switch immediately instead of after the queue rebuild.
                Utils.runOnMainThread(() -> {
                    if (oldPos >= 0 && oldPos < listStore.getNItems()) {
                        listStore.getItem(oldPos).getSongInfo().setIsPlaying(false);
                    }
                    targetSong.setIsPlaying(true);
                });
                this.onPlay.accept(targetSong);
            }

            // Wait for the queue display to finish rebuilding before returning.
            replaceQueueSlots(a.queue(), a.position()).join();
        });
    }


    public record PlayQueueState (
            Optional<ObjectIdentifier> playContext,
            Optional<Integer> position,
            Optional<String> playingItemId,
            PlayMode playMode
    ){}
    private void notifyState() {
        var next = getState();
        this.onStateChanged.accept(next);
    }


    private void onStreamEnded(GstPlaybinPlayer.StreamEndCause cause) {
        if (cause == GstPlaybinPlayer.StreamEndCause.ERROR) {
            synchronized (lock) {
                if (playMode == PlayMode.REPEAT_ONE) {
                    // Replaying the same failing source in REPEAT_ONE would just error again
                    // in a tight loop; stay stopped and let the user pick the next action.
                    log.warn("onStreamEnded: stream error in {} mode, not replaying", playMode);
                    return;
                }
            }
        }
        attemptPlayNext();
    }

    public Optional<GSongInfo> peekNext() {
        synchronized (lock) {
            int nextIdx = position.orElse(-1) + 1;
            if (nextIdx >= listStore.getNItems()) {
                return Optional.empty();
            }
            return Optional.of(listStore.get(nextIdx).getSongInfo());
        }
    }

    public void attemptPlayNext() {
        synchronized (lock) {
            if (listStore.isEmpty()) {
                return;
            }

            // REPEAT_ONE: replay current song
            if (playMode == PlayMode.REPEAT_ONE) {
                int currentIdx = position.orElse(-1);
                if (currentIdx >= 0 && currentIdx < listStore.getNItems()) {
                    var queueItem = listStore.get(currentIdx);
                    this.onPlay.accept(queueItem.getSongInfo());
                    // No position change, no state notification needed
                    return;
                }
            }

            int oldIdx = position.orElse(-1);
            int nextIdx = oldIdx + 1;
            if (nextIdx >= listStore.getNItems()) {
                // we have reached the end of the queue
                return;
            }
            var queueItem = listStore.get(nextIdx);
            this.position = Optional.of(nextIdx);
            this.playingItemId = Optional.ofNullable(queueItem.getQueueItemId());
            updateCurrentItemStyling(oldIdx, nextIdx);
            this.onPlay.accept(queueItem.getSongInfo());
            this.notifyState();
        }
    }

    public void attemptPlayPrev() {
        synchronized (lock) {
            if (listStore.isEmpty()) {
                return;
            }
            var state = player.getState();
            // Use the live position: PlayerState.source().position() is only refreshed on
            // discrete events (seek/pause/EOS) and stays stale while PLAYING.
            var currentPlayPosition = player.getCurrentPosition().orElse(Duration.ZERO);
            if (currentPlayPosition.getSeconds() >= 4) {
                if (state.source().isPresent()) {
                    // its likely we can seek this source
                    player.seekTo(Duration.ZERO);
                    return;
                }
            }
            int oldIdx = this.position.orElse(0);
            int prevIdx = oldIdx - 1;
            if (prevIdx < 0) {
                // we have reached before the start of the queue.
                // seek to zero
                player.seekTo(Duration.ZERO);
                return;
            }
            var queueItem = listStore.get(prevIdx);
            this.position = Optional.of(prevIdx);
            this.playingItemId = Optional.ofNullable(queueItem.getQueueItemId());
            updateCurrentItemStyling(oldIdx, prevIdx);
            this.onPlay.accept(queueItem.getSongInfo());
            this.notifyState();
        }
    }

    public void enqueue(SongInfo songInfo) {
        var song = this.songstore.newInstance(songInfo);
        // ListStore mutations must happen on the main thread: the bound ListView reads the
        // model locklessly there. Position bookkeeping runs in the same locked section so it
        // stays consistent with the store contents. The caller does not hold the lock while
        // waiting on the main thread (see replaceQueueSlots for why that would deadlock).
        Utils.runOnMainThreadFuture(() -> {
            synchronized (lock) {
                int insertPosition = position.orElse(-1) + 1;
                var queueItemId = PlaylistListViewV2.GPlaylistEntry.makeQueueItemId(song.getSongInfo().albumId(), song.getId(), insertPosition);
                var queueItem = GQueueItem.newInstance(queueItemId, song, GQueueItem.QueueKind.USER_ADDED, insertPosition);
                listStore.insert(insertPosition, queueItem);
                this.notifyState();
            }
        }).join();
    }

    public void enqueueLast(SongInfo songInfo) {
        var song = this.songstore.newInstance(songInfo);
        // Main-thread mutation + join without holding the lock; see enqueue.
        Utils.runOnMainThreadFuture(() -> {
            synchronized (lock) {
                int currentPos = position.orElse(-1);
                int insertPos = currentPos + 1;
                for (int i = currentPos + 1; i < listStore.getNItems(); i++) {
                    if (listStore.get(i).getIsUserQueued()) {
                        insertPos = i + 1;
                    } else {
                        break;
                    }
                }
                var queueItemId = PlaylistListViewV2.GPlaylistEntry.makeQueueItemId(
                        song.getSongInfo().albumId(),
                        song.getId(),
                        insertPos
                );
                var queueItem = GQueueItem.newInstance(queueItemId, song, GQueueItem.QueueKind.USER_ADDED, insertPos);
                listStore.insert(insertPos, queueItem);
                this.notifyState();
            }
        }).join();
    }

    public void removeAt(int index) {
        // Main-thread mutation + join without holding the lock; see enqueue.
        Utils.runOnMainThreadFuture(() -> {
            synchronized (lock) {
                if (index < 0 || index >= listStore.getNItems()) {
                    log.warn("removeAt: invalid index={}", index);
                    return;
                }
                int currentPos = position.orElse(-1);
                listStore.removeAt(index);
                if (index < currentPos) {
                    // The playing row shifted up by one; its queueItemId is unchanged,
                    // so playingItemId stays as-is.
                    this.position = Optional.of(currentPos - 1);
                } else if (index == currentPos) {
                    // The current song is removed from the queue but keeps playing.
                    // Decrement position so that "next" plays the song that was after
                    // the removed one (now shifted into the old slot).
                    this.position = currentPos > 0
                            ? Optional.of(currentPos - 1)
                            : Optional.empty();
                    // The playing song no longer has a queue row: highlight nothing.
                    // Deriving the id from the shifted position would highlight the
                    // previous row instead.
                    this.playingItemId = Optional.empty();
                }
                this.notifyState();
            }
        }).join();
    }

    public CompletableFuture<Void> replaceQueueSlots(List<PlayerAction.QueueSlot> slots, Optional<Integer> startPosition) {
        return Utils.doAsync(() -> {
            var newList = new GQueueItem[slots.size()];
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                var song = this.songstore.newInstance(slot.song());
                newList[i] = GQueueItem.newInstance(slot.id(), song, GQueueItem.QueueKind.AUTOMATIC, i);
            }

            boolean isShuffleMode;
            int oldPos;
            // Commit the new play identity (position + playingItemId) eagerly, before the
            // main-thread store rebuild, so the eager state snapshot in
            // AppManager.loadSourceAsync already sees it (see playAndReplaceQueue).
            synchronized (lock) {
                isShuffleMode = playMode == PlayMode.SHUFFLE;
                oldPos = this.position.orElse(-1);
                this.position = startPosition.filter(pos -> pos >= 0 && pos < newList.length);
                this.playingItemId = this.position.map(pos -> newList[pos].getQueueItemId());
            }

            // The store swap runs on the main thread. IMPORTANT: the lock must NOT be held
            // while waiting on the main thread. The GStreamer bus watch runs on the main
            // thread and takes this lock via attemptPlayNext on EOS — and bus messages
            // dispatch at higher priority than idles, so holding the lock across this join
            // deadlocks the whole UI when a track ends at the wrong moment.
            Utils.runOnMainThreadFuture(() -> {
                synchronized (lock) {
                    // Clear isPlaying on the previously playing GSongInfo before replacing.
                    // GSongInfo instances are globally shared, so stale isPlaying=true would
                    // leak into the new queue if the same song appears at a different position.
                    // TODO: updating prev song is-playing should probably be done in AppState and AppManager by the switch to a new song
                    if (oldPos >= 0 && oldPos < listStore.getNItems()) {
                        listStore.getItem(oldPos).getSongInfo().setIsPlaying(false);
                    }
                    this.listStore.removeAll();
                    this.listStore.splice(0, 0, newList);
                    var pos = this.position.filter(p -> p >= 0 && p < listStore.getNItems());
                    if (pos.isPresent()) {
                        listStore.getItem(pos.get()).getSongInfo().setIsPlaying(true);
                    }
                }
            }).join();

            if (isShuffleMode) {
                shuffle(false);
            }
            this.notifyState();
        });
    }

    public CompletableFuture<Void> replaceQueueSlots(List<PlayerAction.QueueSlot> slots, int startPosition) {
        return replaceQueueSlots(slots, Optional.of(startPosition));
    }

    /** Convenience method for callers that don't need UUID tracking (e.g. tests). */
    public CompletableFuture<Void> replaceQueue(List<SongInfo> songs, int startPosition) {
        var slots = songs.stream()
                .map(s -> new PlayerAction.QueueSlot(UUID.randomUUID().toString(), s))
                .toList();
        return replaceQueueSlots(slots, startPosition);
    }

    private void updateCurrentItemStyling(int oldPosition, int newPosition) {
        Utils.runOnMainThread(() -> {
            if (oldPosition != newPosition && oldPosition >= 0 && oldPosition < listStore.getNItems()) {
                listStore.getItem(oldPosition).getSongInfo().setIsPlaying(false);
            }
            if (newPosition >= 0 && newPosition < listStore.getNItems()) {
                var nextItem = listStore.getItem(newPosition);
                nextItem.getSongInfo().setIsPlaying(true);
                log.info("updateCurrentItemStyling: nextItem={}", nextItem.getId());
            }
        });
    }

    public void shuffle() {
        shuffle(true);
    }

    private void shuffle(boolean doNotify) {
        synchronized (lock) {
            playMode = PlayMode.SHUFFLE;
            if (listStore.getNItems() <= 1) {
                return;
            }
        }
        // Reorder on the main thread; the lock must not be held while waiting on the main
        // thread (see replaceQueueSlots).
        Utils.runOnMainThreadFuture(() -> {
            synchronized (lock) {
                int oldPos = position.orElse(-1);
                GQueueItem currentItem = oldPos >= 0 && oldPos < listStore.getNItems()
                        ? listStore.getItem(oldPos)
                        : null;

                // Assign random positive shuffle numbers to all items
                var random = new Random();
                for (int i = 0; i < listStore.getNItems(); i++) {
                    // Use absolute value to ensure positive, add 1 to avoid 0
                    listStore.getItem(i).setShuffleOrder(random.nextInt(1, Integer.MAX_VALUE));
                }

                // Set current song's shuffle order to minimum so it sorts first
                if (currentItem != null) {
                    currentItem.setShuffleOrder(Integer.MIN_VALUE);
                }

                // Extract items and sort by shuffleOrder (current song will be first)
                var items = new ArrayList<GQueueItem>();
                for (int i = 0; i < listStore.getNItems(); i++) {
                    items.add(listStore.getItem(i));
                }
                items.sort(Comparator.comparingInt(GQueueItem::getShuffleOrder));

                listStore.removeAll();
                listStore.splice(0, 0, items.toArray(GQueueItem[]::new));

                // Current song is now at position 0
                if (currentItem != null) {
                    position = Optional.of(0);
                }
            }
        }).join();

        if (doNotify) {
            notifyState();
        }
    }

    public void setPlayMode(PlayMode mode) {
        synchronized (lock) {
            this.playMode = mode;
            notifyState();
        }
    }

    public void unshuffle() {
        synchronized (lock) {
            if (playMode == PlayMode.NORMAL || listStore.getNItems() <= 1) {
                return;
            }
        }
        // Reorder on the main thread; the lock must not be held while waiting on the main
        // thread (see replaceQueueSlots).
        Utils.runOnMainThreadFuture(() -> {
            synchronized (lock) {
                // Extract items, sort by originalOrder, rebuild store
                var items = new ArrayList<GQueueItem>();
                for (int i = 0; i < listStore.getNItems(); i++) {
                    items.add(listStore.getItem(i));
                }
                items.sort(Comparator.comparingInt(GQueueItem::getOriginalOrder));

                // Find new position of currently playing song
                int oldPos = position.orElse(-1);
                GQueueItem currentItem = oldPos >= 0 && oldPos < listStore.getNItems()
                        ? listStore.getItem(oldPos)
                        : null;

                listStore.removeAll();
                listStore.splice(0, 0, items.toArray(GQueueItem[]::new));

                // Update position to track the same song
                if (currentItem != null) {
                    position = Optional.of(items.indexOf(currentItem));
                }

                playMode = PlayMode.NORMAL;
            }
        }).join();
        notifyState();
    }

    @Override
    public void close() throws Exception {
        this.player.removeOnStreamEnded(this.streamEndedListener);
    }

}
