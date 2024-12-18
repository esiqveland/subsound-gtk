package com.github.subsound.app.state;

import com.github.subsound.app.state.PlayerAction.Enqueue;
import com.github.subsound.app.state.PlayerAction.PlayPositionInQueue;
import com.github.subsound.configuration.Config;
import com.github.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.SongCache;
import com.github.subsound.persistence.SongCache.CacheSong;
import com.github.subsound.persistence.SongCache.LoadSongResult;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.sound.PlaybinPlayer.AudioSource;
import com.github.subsound.sound.PlaybinPlayer.Source;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.utils.Utils;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.gnome.adw.ToastOverlay;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static com.github.subsound.app.state.AppManager.NowPlaying.State.LOADING;
import static com.github.subsound.app.state.AppManager.NowPlaying.State.READY;

public class AppManager {
    private static final Logger log = LoggerFactory.getLogger(AppManager.class);

    public static final Executor ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    public static final String SERVER_ID = "123";

    private final Config config;
    private final PlaybinPlayer player;
    private final PlayQueue playQueue;
    private final SongCache songCache;
    private final ThumbnailCache thumbnailCache;
    private final AtomicReference<ServerClient> client;
    private final BehaviorSubject<AppState> currentState;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private ToastOverlay toastOverlay;
    private AppNavigation navigator;

    public AppManager(
            Config config,
            PlaybinPlayer player,
            SongCache songCache,
            ThumbnailCache thumbnailCache,
            Optional<ServerClient> client
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
        this.client = new AtomicReference<>();
        client.ifPresent(this.client::set);
        player.onStateChanged(next -> this.setState(old -> new AppState(
                old.nowPlaying, next, old.queue
        )));
        this.currentState = BehaviorSubject.createDefault(buildState());
        var disposable = this.currentState
                .throttleLatest(100, TimeUnit.MILLISECONDS, true)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .forEach(next -> this.notifyListeners());
    }

    private AppState buildState() {
        return new AppState(Optional.empty(), this.player.getState(), this.playQueue.getState());
    }

    public AppState getState() {
        return this.currentState.getValue();
    }

    public ThumbnailCache getThumbnailCache() {
        return thumbnailCache;
    }

    public <T> T useClient(Function<ServerClient, T> useFunc) {
        return useFunc.apply(this.client.get());
    }

    public Config getConfig() {
        return config;
    }

    public AppManager setToastOverlay(ToastOverlay toastOverlay) {
        this.toastOverlay = toastOverlay;
        return this;
    }

    public void playPause() {
        if (this.currentState.getValue().player.state().isPlaying()) {
            this.pause();
        } else {
            if (this.currentState.getValue().player.source().isPresent()) {
                this.play();
            }
        }
    }

    public void navigateTo(AppNavigation.AppRoute route) {
        this.navigator.navigateTo(route);
    }

    public void setNavigator(AppNavigation appNavigation) {
        this.navigator = appNavigation;
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
        AppState appState = this.currentState.getValue();
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
                case PlayerAction.Toast t -> this.toast(t);

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

    private void toast(PlayerAction.Toast t) {
        if (this.toastOverlay == null) {
            log.warn("unable to toast because toastOverlay={}", this.toastOverlay);
            return;
        }
        Utils.runOnMainThread(() -> {
            this.toastOverlay.addToast(t.toast());
        });
    }

    private void saveConfig(PlayerAction.SaveConfig settings) {
        this.config.onboarding = OnboardingState.DONE;
        this.config.serverConfig = new Config.ServerConfig(
                this.config.dataDir,
                SERVER_ID,
                settings.next().type(),
                settings.next().serverUrl(),
                settings.next().username(),
                settings.next().password()
        );
        try {
            this.config.saveToFile();
            var newClient = ServerClient.create(this.config.serverConfig);
            this.client.set(newClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void unstarSong(PlayerAction.Unstar a) {
        this.client.get().unStarId(a.song().id());
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
        this.client.get().starId(a.song().id());
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
            this.currentState.onNext(modifier.apply(this.currentState.getValue()));
        } finally {
            lock.unlock();
        }
    }

    private void notifyListeners() {
        var state = this.currentState.getValue();
        Thread.startVirtualThread(() -> {
            for (StateListener stateListener : listeners) {
                stateListener.onStateChanged(state);
            }
        });
    }
}
