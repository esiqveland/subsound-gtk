package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.END_OF_STREAM;

public class PlayQueue implements AutoCloseable, PlaybinPlayer.OnStateChanged {
    private static final Logger log = LoggerFactory.getLogger(PlayQueue.class);

    private final PlaybinPlayer player;
    private final Consumer<PlayQueueState> onStateChanged;
    private final Consumer<SongInfo> onPlay;
    private final CopyOnWriteArrayList<SongInfo> playQueue = new CopyOnWriteArrayList<>();
    private Optional<Integer> position = Optional.empty();

    public PlayQueue(
            PlaybinPlayer player,
            Consumer<PlayQueueState> onStateChanged,
            Consumer<SongInfo> onPlay
    ) {
        this.player = player;
        this.onStateChanged = onStateChanged;
        this.onPlay = onPlay;
        this.player.onStateChanged(this);
    }

    public PlayQueueState getState() {
        return new PlayQueueState(
                Collections.unmodifiableList(playQueue),
                position
        );
    }

    public void playPosition(int position) {
        if (position < 0) {
            log.warn("playPosition: can not play invalid position={}", position);
            return;
        }
        if (position >= playQueue.size()) {
            log.warn("playPosition: can not play invalid position={}", position);
            return;
        }
        SongInfo songInfo = playQueue.get(position);
        this.position = Optional.of(position);
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
        int nextIdx = position.orElse(-1) + 1;
        if (nextIdx >= playQueue.size()) {
            // we have reached the end of the queue
            return;
        }
        var songInfo = playQueue.get(nextIdx);
        this.position = Optional.of(nextIdx);
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
        int prevIdx = this.position.orElse(0) - 1;
        if (prevIdx < 0) {
            // we have reached before the start of the queue.
            // seek to zero
            player.seekTo(Duration.ZERO);
            return;
        }
        var songInfo = playQueue.get(prevIdx);
        this.position = Optional.of(prevIdx);
        this.onPlay.accept(songInfo);
        this.notifyState();
    }

    public void enqueue(SongInfo songInfo) {
        playQueue.add(songInfo);
        this.notifyState();
    }

    public void replaceQueue(List<SongInfo> queue, Optional<Integer> startPosition) {
        playQueue.clear();
        playQueue.addAll(queue);
        position = startPosition;
        this.notifyState();
    }

    public void replaceQueue(List<SongInfo> queue) {
        replaceQueue(queue, Optional.empty());
    }

    public void replaceQueue(List<SongInfo> queue, int startPosition) {
        replaceQueue(queue, Optional.of(startPosition));
    }

    @Override
    public void close() throws Exception {
        this.player.removeOnStateChanged(this);
    }

}
