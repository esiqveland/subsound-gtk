package com.github.subsound.app.state;

import com.github.subsound.sound.Player;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.sound.PlaybinPlayer.Source;
import com.github.subsound.ui.models.GQueueItem;
import com.github.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.github.subsound.sound.PlaybinPlayer.PlayerStates.END_OF_STREAM;

public class PlayQueue implements AutoCloseable, PlaybinPlayer.OnStateChanged {
    private static final Logger log = LoggerFactory.getLogger(PlayQueue.class);

    private final Object lock = new Object();
    private final Player player;
    private final Consumer<PlayQueueState> onStateChanged;
    private final Consumer<SongInfo> onPlay;
    private final ListStore<GQueueItem> listStore = new ListStore<>(GQueueItem.gtype);
    private Optional<Integer> position = Optional.empty();

    public PlayQueue(
            Player player,
            Consumer<PlayQueueState> onStateChanged,
            Consumer<SongInfo> onPlay
    ) {
        this.player = player;
        this.onStateChanged = onStateChanged;
        this.onPlay = onPlay;
        this.player.onStateChanged(this);
    }

    public ListStore<GQueueItem> getListStore() {
        return this.listStore;
    }

    public PlayQueueState getState() {
        synchronized (lock) {
            return new PlayQueueState(position);
        }
    }

    public void playPosition(int newPosition) {
        synchronized (lock) {
            if (newPosition < 0) {
                log.warn("playPosition: can not play invalid position={}", newPosition);
                return;
            }
            if (newPosition >= listStore.size()) {
                log.warn("playPosition: can not play invalid position={}", newPosition);
                return;
            }
            SongInfo songInfo = listStore.get(newPosition).songInfo();
            int oldPosition = this.position.orElse(-1);
            this.position = Optional.of(newPosition);
            updateCurrentItemStyling(oldPosition, newPosition);
            this.onPlay.accept(songInfo);
            this.notifyState();
        }
    }

    public record PlayQueueState (
            Optional<Integer> position
    ){}
    private void notifyState() {
        var next = getState();
        this.onStateChanged.accept(next);
    }


    @Override
    public void onState(PlaybinPlayer.PlayerState st) {
        if (st.state() == END_OF_STREAM) {
            attemptPlayNext();
        }
    }

    public void attemptPlayNext() {
        synchronized (lock) {
            if (listStore.isEmpty()) {
                return;
            }
            int oldIdx = position.orElse(-1);
            int nextIdx = oldIdx + 1;
            if (nextIdx >= listStore.size()) {
                // we have reached the end of the queue
                return;
            }
            var songInfo = listStore.get(nextIdx).songInfo();
            this.position = Optional.of(nextIdx);
            updateCurrentItemStyling(oldIdx, nextIdx);
            this.onPlay.accept(songInfo);
            this.notifyState();
        }
    }

    public void attemptPlayPrev() {
        synchronized (lock) {
            if (listStore.isEmpty()) {
                return;
            }
            var state = player.getState();
            var currentPlayPosition = state.source().flatMap(Source::position).orElse(Duration.ZERO);
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
            var songInfo = listStore.get(prevIdx).songInfo();
            this.position = Optional.of(prevIdx);
            updateCurrentItemStyling(oldIdx, prevIdx);
            this.onPlay.accept(songInfo);
            this.notifyState();
        }
    }

    public void enqueue(SongInfo songInfo) {
        synchronized (lock) {
            int insertPosition = position.orElse(-1) + 1;
            listStore.insert(insertPosition, GQueueItem.newInstance(songInfo, GQueueItem.QueueKind.USER_ADDED));
            this.notifyState();
        }
    }

    public void enqueueLast(SongInfo songInfo) {
        synchronized (lock) {
            int currentPos = position.orElse(-1);
            int insertPos = currentPos + 1;
            for (int i = currentPos + 1; i < listStore.size(); i++) {
                if (listStore.get(i).getIsUserQueued()) {
                    insertPos = i + 1;
                } else {
                    break;
                }
            }
            listStore.insert(insertPos, GQueueItem.newInstance(songInfo, GQueueItem.QueueKind.USER_ADDED));
            this.notifyState();
        }
    }

    public void removeAt(int index) {
        synchronized (lock) {
            if (index < 0 || index >= listStore.size()) {
                log.warn("removeAt: invalid index={}", index);
                return;
            }
            int currentPos = position.orElse(-1);
            listStore.remove(index);
            if (index < currentPos) {
                this.position = Optional.of(currentPos - 1);
            } else if (index == currentPos) {
                // The current song is removed from the queue but keeps playing.
                // Decrement position so that "next" plays the song that was after
                // the removed one (now shifted into the old slot).
                this.position = currentPos > 0
                        ? Optional.of(currentPos - 1)
                        : Optional.empty();
            }
            this.notifyState();
        }
    }

    public void replaceQueue(List<SongInfo> newQueue, Optional<Integer> startPosition) {
        synchronized (lock) {
            listStore.removeAll();
            newQueue.stream()
                    .map(GQueueItem::newInstance)
                    .forEach(listStore::append);
            position = startPosition;
            startPosition.ifPresent(pos -> {
                if (pos >= 0 && pos < listStore.getNItems()) {
                    listStore.getItem(pos).setIsCurrent(true);
                }
            });
            this.notifyState();
        }
    }

    public void replaceQueue(List<SongInfo> queue) {
        replaceQueue(queue, Optional.empty());
    }

    public void replaceQueue(List<SongInfo> queue, int startPosition) {
        replaceQueue(queue, Optional.of(startPosition));
    }

    private void updateCurrentItemStyling(int oldPosition, int newPosition) {
        Utils.runOnMainThread(() -> {
            if (oldPosition >= 0 && oldPosition < listStore.getNItems()) {
                listStore.getItem(oldPosition).setIsCurrent(false);
            }
            if (newPosition >= 0 && newPosition < listStore.getNItems()) {
                var nextItem = listStore.getItem(newPosition);
                nextItem.setIsCurrent(true);
                log.info("updateCurrentItemStyling: nextItem={}", nextItem.getId());
            }
        });
    }

    @Override
    public void close() throws Exception {
        this.player.removeOnStateChanged(this);
    }

}
