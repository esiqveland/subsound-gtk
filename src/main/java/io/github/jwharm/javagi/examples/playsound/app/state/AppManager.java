package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction.Enqueue;
import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction.PlayPositionInQueue;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClientSongInfoBuilder;
import io.github.jwharm.javagi.examples.playsound.integration.servers.subsonic.SubsonicClient;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.CacheSong;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.LoadSongResult;
import io.github.jwharm.javagi.examples.playsound.persistence.ThumbnailCache;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AppManager {
    private static final Logger log = LoggerFactory.getLogger(AppManager.class);

    public static final Executor ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    public static final String SERVER_ID = "123";

    private final PlaybinPlayer player;
    private final PlayQueue playQueue;
    private final SongCache songCache;
    private final ThumbnailCache thumbnailCache;
    private final SubsonicClient client;
    private final AtomicReference<AppState> currentState = new AtomicReference<>();
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    public AppManager(
            PlaybinPlayer player,
            SongCache songCache,
            ThumbnailCache thumbnailCache,
            SubsonicClient client
    ) {
        this.player = player;
        this.songCache = songCache;
        this.thumbnailCache = thumbnailCache;
        this.playQueue = new PlayQueue(
                player,
                nextState -> this.setState(old -> new AppState(
                        old.nowPlaying,
                        old.player,
                        nextState
                )),
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

    public SubsonicClient getClient() {
        return this.client;
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

    @RecordBuilderFull
    public record NowPlaying (
            SongInfo song,
            LoadSongResult cacheResult
    ) implements AppManagerNowPlayingBuilder.With {
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

    private LoadSongResult loadSourceSync(SongInfo songInfo) {
        LoadSongResult song = songCache.getSong(new CacheSong(
                SERVER_ID,
                songInfo.id(),
                songInfo.streamUri(),
                songInfo.suffix(),
                songInfo.streamSuffix(),
                songInfo.size()
        ));
        log.info("cached: result={} id={} title={}", song.result().name(), songInfo.id(), songInfo.title());
        this.setState(old -> new AppState(
                Optional.of(new NowPlaying(songInfo, song)),
                old.player,
                old.queue
        ));
        this.player.setSource(song.uri());
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
            }
        });
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

    private void setState(Function<AppState, AppState> modifier) {
        this.currentState.set(modifier.apply(this.currentState.get()));
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
