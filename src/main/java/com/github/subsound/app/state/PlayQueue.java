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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.github.subsound.sound.PlaybinPlayer.PlayerStates.END_OF_STREAM;

public class PlayQueue implements AutoCloseable, PlaybinPlayer.OnStateChanged {
    private static final Logger log = LoggerFactory.getLogger(PlayQueue.class);

    private final Player player;
    private final Consumer<PlayQueueState> onStateChanged;
    private final Consumer<SongInfo> onPlay;
    private final CopyOnWriteArrayList<SongInfo> playQueue = new CopyOnWriteArrayList<>();
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
        return new PlayQueueState(
                Collections.unmodifiableList(playQueue),
                position
        );
    }

    public void playPosition(int newPosition) {
        if (newPosition < 0) {
            log.warn("playPosition: can not play invalid position={}", newPosition);
            return;
        }
        if (newPosition >= playQueue.size()) {
            log.warn("playPosition: can not play invalid position={}", newPosition);
            return;
        }
        SongInfo songInfo = playQueue.get(newPosition);
        int oldPosition = this.position.orElse(-1);
        this.position = Optional.of(newPosition);
        updateCurrentItemStyling(oldPosition, newPosition);
        this.onPlay.accept(songInfo);
        this.notifyState();
    }

    public record PlayQueueState (
            List<SongInfo> playQueue,
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
        if (playQueue.isEmpty()) {
            return;
        }
        int oldIdx = position.orElse(-1);
        int nextIdx = oldIdx + 1;
        if (nextIdx >= playQueue.size()) {
            // we have reached the end of the queue
            return;
        }
        var songInfo = playQueue.get(nextIdx);
        this.position = Optional.of(nextIdx);
        updateCurrentItemStyling(oldIdx, nextIdx);
        this.onPlay.accept(songInfo);
        this.notifyState();
    }

    public void attemptPlayPrev() {
        if (playQueue.isEmpty()) {
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
        var songInfo = playQueue.get(prevIdx);
        this.position = Optional.of(prevIdx);
        updateCurrentItemStyling(oldIdx, prevIdx);
        this.onPlay.accept(songInfo);
        this.notifyState();
    }

    public void enqueue(SongInfo songInfo) {
        int insertPosition = position.orElse(-1) + 1;
        playQueue.add(insertPosition, songInfo);
        Utils.runOnMainThread(() -> {
            listStore.insert(insertPosition, GQueueItem.newInstance(songInfo));
        });
        this.notifyState();
    }

    public void replaceQueue(List<SongInfo> queue, Optional<Integer> startPosition) {
        playQueue.clear();
        playQueue.addAll(queue);
        position = startPosition;
        Utils.runOnMainThread(() -> {
            listStore.removeAll();
            queue.stream()
                    .map(GQueueItem::newInstance)
                    .forEach(listStore::append);
            startPosition.ifPresent(pos -> {
                if (pos >= 0 && pos < listStore.getNItems()) {
                    listStore.getItem(pos).setIsCurrent(true);
                }
            });
        });
        this.notifyState();
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
