package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction.Enqueue;
import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction.PlayPositionInQueue;
import io.github.jwharm.javagi.examples.playsound.configuration.Config;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.CacheSong;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.LoadSongResult;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.AudioSource;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.Source;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static io.github.jwharm.javagi.examples.playsound.app.state.AppManager.NowPlaying.State.LOADING;
import static io.github.jwharm.javagi.examples.playsound.app.state.AppManager.NowPlaying.State.READY;

public class AppManager {
    private static final Logger log = LoggerFactory.getLogger(AppManager.class);

    public static final Executor ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    public static final String SERVER_ID = "123";

    private final Config config;
    private final PlaybinPlayer player;
    private final PlayQueue playQueue;
    private final SongCache songCache;
    private final ThumbnailCache thumbnailCache;
    private final ServerClient client;
    private final AtomicReference<AppState> currentState = new AtomicReference<>();
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    public AppManager(
            Config config,
            PlaybinPlayer player,
            SongCache songCache,
            ThumbnailCache thumbnailCache,
            ServerClient client
    ) {
        this.config = config;
        this.player = player;
        this.songCache = songCache;
        this.thumbnailCache = thumbnailCache;
        this.playQueue = new PlayQueue(
                player,
                nextState -> this.setState(old -> old.withQueue(nextState)),
                this::loadSource
        );
        this.client = client;
        player.onStateChanged(next -> this.setState(old -> new AppState(
                old.nowPlaying, next, old.queue
        )));
        this.currentState.set(buildState());
    }

    private AppState buildState() {
        return new AppState(Optional.empty(), this.player.getState(), this.playQueue.getState());
    }

    public AppState getState() {
        return this.currentState.get();
    }

    public ThumbnailCache getThumbnailCache() {
        return thumbnailCache;
    }

    public ServerClient getClient() {
        return this.client;
    }

    public Config getConfig() {
        return config;
    }

    public interface StateListener {
        void onStateChanged(AppState state);
    }

    public void addOnStateChanged(StateListener lis) {
        listeners.add(lis);
    }

    public void removeOnStateChanged(StateListener lis) {
        listeners.remove(lis);
    }

    public record BufferingProgress(long total, long count) {}
    @RecordBuilderFull
    public record NowPlaying (
            SongInfo song,
            State state,
            UUID requestId,
            BufferingProgress bufferingProgress,
            Optional<LoadSongResult> cacheResult
    ) implements AppManagerNowPlayingBuilder.With {
        public enum State {
            LOADING,
            READY,
        }
    }

    @RecordBuilderFull
    public record AppState(
            Optional<NowPlaying> nowPlaying,
            PlaybinPlayer.PlayerState player,
            PlayQueue.PlayQueueState queue
    ) implements AppManagerAppStateBuilder.With {
    }

    public void enqueue(SongInfo songInfo) {
        this.playQueue.enqueue(songInfo);
    }

    public CompletableFuture<LoadSongResult> loadSource(SongInfo songInfo) {
        return CompletableFuture.supplyAsync(
                () -> this.loadSourceSync(songInfo),
                ASYNC_EXECUTOR
        );
    }

    @FunctionalInterface
    public interface ProgressHandler {
        interface ProgressUpdate {}
        record Start(SongInfo songInfo) implements ProgressUpdate {}
        record Update(SongInfo songInfo, float percent) implements ProgressUpdate {}
        record Completed(SongInfo songInfo) implements ProgressUpdate {}

        void update(ProgressUpdate u);
    }

    private LoadSongResult loadSourceSync(SongInfo songInfo) {
        this.pause();
        UUID requestId = UUID.randomUUID();
        this.setState(old -> old.with()
                .nowPlaying(Optional.of(new NowPlaying(
                        songInfo,
                        LOADING,
                        requestId,
                        new BufferingProgress(songInfo.size(), 0),
                        Optional.empty()
                )))
                .player(old.player.withSource(Optional.of(new Source(
                        songInfo.streamUri(),
                        Optional.of(Duration.ZERO),
                        Optional.of(songInfo.duration())
                ))))
                .build()
        );
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        LoadSongResult song = songCache.getSong(new CacheSong(
                SERVER_ID,
                songInfo.id(),
                songInfo.streamUri(),
                songInfo.suffix(),
                songInfo.streamSuffix(),
                songInfo.size(),
                (total, count) -> {
                    if (isCancelled.get()) {
                        return;
                    }
                    this.setState(old -> {
                        Optional<NowPlaying> nowPlaying = old.nowPlaying();
                        if (nowPlaying.isEmpty()) {
                            isCancelled.set(true);
                            return old;
                        }
                        var np = nowPlaying.get();
                        if (!np.requestId.equals(requestId)) {
                            isCancelled.set(true);
                            return old;
                        }
                        return old.withNowPlaying(Optional.of(np.withBufferingProgress(
                                new BufferingProgress(total, count)
                        )));
                    });
                }
        ));
        log.info("cached: result={} id={} title={}", song.result().name(), songInfo.id(), songInfo.title());
        AppState appState = this.currentState.get();
        var currentSongId = appState.nowPlaying().map(NowPlaying::song).map(SongInfo::id).orElse("");
        if (!currentSongId.equals(songInfo.id())) {
            // we changed song while loading. Ignore this and do nothing:
            return song;
        }
        this.player.setSource(
                new AudioSource(song.uri(), songInfo.duration()),
                true
        );
        this.setState(old -> old.withNowPlaying(Optional.of(new NowPlaying(
                songInfo,
                READY,
                requestId,
                new BufferingProgress(1000, 1000),
                Optional.of(song)
        ))));
        return song;
    }

    public void play() {
        this.player.play();
    }

    public void pause() {
        this.player.pause();
    }

    public void mute() {
        this.player.setMute(true);
    }

    public void unMute() {
        this.player.setMute(false);
    }

    public void seekTo(Duration position) {
        this.player.seekTo(position);
    }

    public void setVolume(double linearVolume) {
        this.player.setVolume(linearVolume);
    }

    public void next() {
        this.playQueue.attemptPlayNext();
    }

    public void prev() {
        this.playQueue.attemptPlayPrev();
    }

    public CompletableFuture<Void> handleAction(PlayerAction action) {
        return Utils.doAsync(() -> {
            log.info("handleAction: payload={}", action);
            switch (action) {
                // config actions:
                case PlayerAction.SaveConfig settings -> this.saveConfig(settings);

                // player actions:
                case Enqueue a -> this.playQueue.enqueue(a.song());
                case PlayPositionInQueue a -> this.playQueue.playPosition(a.position());
                case PlayerAction.PlayQueue a -> {
                    this.playQueue.replaceQueue(a.queue(), a.position());
                    this.playQueue.playPosition(a.position());
                }
                case PlayerAction.Pause a -> this.pause();
                case PlayerAction.Play a -> this.play();
                case PlayerAction.PlayNext a -> this.playQueue.attemptPlayNext();
                case PlayerAction.PlayPrev a -> this.playQueue.attemptPlayPrev();
                case PlayerAction.SeekTo seekTo -> this.player.seekTo(seekTo.position());

                // API actions
                case PlayerAction.Star a -> this.starSong(a);
                case PlayerAction.Unstar a -> this.unstarSong(a);
                case PlayerAction.PlaySong playSong -> this.loadSource(playSong.song());
            }
        });
    }

    private void saveConfig(PlayerAction.SaveConfig settings) {
        this.config.serverConfig = new Config.ServerConfig(
                settings.next().type(),
                settings.next().serverUrl(),
                settings.next().username(),
                settings.next().password()
        );
        try {
            this.config.saveToFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void unstarSong(PlayerAction.Unstar a) {
        this.client.unStarId(a.song().id());
        setState(appState -> appState.nowPlaying()
                .map(nowPlaying -> {
                    var song = nowPlaying.song();
                    if (song.id().equals(a.song().id())) {
                        var updated = nowPlaying.withSong(song.withStarred(Optional.empty()));
                        return appState.withNowPlaying(Optional.of(updated));
                    } else {
                        return appState;
                    }
                }).orElse(appState));
    }

    private void starSong(PlayerAction.Star a) {
        this.client.starId(a.song().id());
        setState(appState -> appState.nowPlaying()
                .map(nowPlaying -> {
                    var song = nowPlaying.song();
                    if (song.id().equals(a.song().id())) {
                        var updated = nowPlaying.withSong(song.withStarred(Optional.ofNullable(Instant.now())));
                        return appState.withNowPlaying(Optional.of(updated));
                    } else {
                        return appState;
                    }
                }).orElse(appState));
    }

    private final Lock lock = new ReentrantLock();
    private void setState(Function<AppState, AppState> modifier) {
        try {
            lock.lock();
            this.currentState.set(modifier.apply(this.currentState.get()));
        } finally {
            lock.unlock();
        }
        this.notifyListeners();
    }

    private void notifyListeners() {
        var state = this.currentState.get();
        Thread.startVirtualThread(() -> {
            for (StateListener stateListener : listeners) {
                stateListener.onStateChanged(state);
            }
        });
    }
}
