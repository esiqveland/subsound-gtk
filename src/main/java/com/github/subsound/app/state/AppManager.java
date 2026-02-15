package com.github.subsound.app.state;

import com.github.subsound.app.state.NetworkMonitoring.NetworkState;
import com.github.subsound.app.state.PlayerAction.Enqueue;
import com.github.subsound.app.state.PlayerAction.PlayPositionInQueue;
import com.github.subsound.configuration.Config;
import com.github.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import com.github.subsound.integration.ServerClient;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.persistence.CachingClient;
import com.github.subsound.persistence.DownloadManager;
import com.github.subsound.persistence.ScrobbleService;
import com.github.subsound.persistence.SongCache;
import com.github.subsound.persistence.SongCache.CacheSong;
import com.github.subsound.persistence.SongCache.LoadSongResult;
import com.github.subsound.persistence.ThumbnailCache;
import com.github.subsound.persistence.database.Database;
import com.github.subsound.persistence.database.DatabaseServerService;
import com.github.subsound.persistence.database.DownloadQueueItem;
import com.github.subsound.persistence.database.PlayerConfig;
import com.github.subsound.persistence.database.PlayerConfigService;
import com.github.subsound.persistence.database.PlayerStateJson;
import com.github.subsound.sound.PlaybinPlayer;
import com.github.subsound.sound.PlaybinPlayer.AudioSource;
import com.github.subsound.sound.PlaybinPlayer.Source;
import com.github.subsound.ui.components.AppNavigation;
import com.github.subsound.ui.models.GQueueItem;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.ui.models.GSongInfo.GSongStore;
import com.github.subsound.utils.Utils;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.gnome.adw.ToastOverlay;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    public static final String SERVER_ID = "8034888b-5544-4dbe-b9ec-be5ad02831cd";

    private final Config config;
    private final PlaybinPlayer player;
    private final PlayQueue playQueue;
    private final SongCache songCache;
    private final ThumbnailCache thumbnailCache;
    private final AtomicReference<CachingClient> client;
    private final BehaviorSubject<AppState> currentState;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final GSongStore gSongStore = new GSongStore();
    private final StarredListStore starredList;
    private final PlaylistsStore playlistsStore;
    private final SearchResultStore searchResultStore;
    private final ScheduledExecutorService preferenceSaveScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Database database;
    private final DatabaseServerService dbService;
    private final PlayerConfigService playerConfigService;
    private final DownloadManager downloadManager;
    private final ScrobbleService scrobbleService;
    private final NetworkMonitoring networkMonitor;
    private volatile ScheduledFuture<?> pendingPreferenceSave;
    private volatile UUID scrobbledForRequestId = null;

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
        this.networkMonitor = new GioNetworkStatusMonitor(this::updateNetworkState);
        this.playQueue = new PlayQueue(
                player,
                this.gSongStore,
                nextState -> this.setState(old -> old.withQueue(nextState)),
                songInfo -> loadSource(new PlayerAction.PlaySong(songInfo.getSongInfo()))
        );
        this.database = new Database();
        this.playerConfigService = new PlayerConfigService(this.database);
        // Apply saved player preferences (volume/mute) from DB
        // Must be done after currentState is initialized since setVolume/setMute trigger state changes
        var savedConfig = this.playerConfigService.loadPlayerConfig();
        var savedPlayerState = savedConfig
                .map(PlayerConfig::playerState)
                .orElse(PlayerStateJson.defaultState());

        var savedServerId = savedConfig.map(PlayerConfig::serverId).orElse(SERVER_ID);
        this.dbService = new DatabaseServerService(
                UUID.fromString(savedServerId),
                this.database
        );

        this.client = new AtomicReference<>();
        client.ifPresent(c -> this.client.set(wrapWithCaching(c)));

        player.onStateChanged(next -> {
            this.setState(old -> old.withPlayer(next));
            this.checkScrobble(next);
        });

        this.currentState = BehaviorSubject.createDefault(buildState());
        var disposable = this.currentState
                .throttleLatest(100, TimeUnit.MILLISECONDS, true)
                //.throttleLatest(50, TimeUnit.MILLISECONDS, true)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .forEach(next -> this.notifyListeners());

        // restore saved volume:
        this.player.setVolume(savedPlayerState.volume());
        this.player.setMute(savedPlayerState.muted());

        // Restore last playing song from DB (without auto-playing)
        var lastPlayback = savedPlayerState.currentPlayback();
        if (lastPlayback != null && lastPlayback.songId() != null && !lastPlayback.songId().isBlank()) {
            restoreLastPlayingSong(lastPlayback);
        }

        this.starredList = new StarredListStore(this);
        this.playlistsStore = new PlaylistsStore(this);
        this.searchResultStore = new SearchResultStore(this.client::get);

        client.ifPresent(c -> {
            this.starredList.refreshAsync();
            this.playlistsStore.refreshListAsync();
        });
        this.downloadManager = new DownloadManager(
                dbService,
                songCache,
                downloadEvent -> {}
        );
        this.scrobbleService = new ScrobbleService(
                dbService,
                () -> this.client.get(),
                () -> this.getState().networkState()
        );
    }

    private void updateNetworkState(Void unused) {
        var next = this.networkMonitor.getState();
        this.setState(appState -> appState.withNetworkState(next));

        // Update CachingClient with network status
        var client = this.client.get();
        client.setNetworkStatus(next.status());
    }

    /**
     * Asynchronously restores the last playing song from the server without auto-playing.
     */
    private void restoreLastPlayingSong(PlayerStateJson.PlaybackPosition storedPlayback) {
        var songId = storedPlayback.songId();
        if (songId == null || songId.isBlank()) {
            // cant restore this...
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                var serverClient = this.client.get();
                if (serverClient == null) {
                    return null;
                }
                return serverClient.getSong(songId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to restore last playing song: songId=%s".formatted(songId), e);
            }
        }, ASYNC_EXECUTOR)
                .thenComposeAsync(songInfo -> this.loadSource(new PlayerAction.PlaySong(songInfo, true)).thenApply(v -> songInfo),
                ASYNC_EXECUTOR)
                .thenComposeAsync(songInfo -> {
                    var position = Duration.ofMillis(storedPlayback.positionMillis());
                    log.info("restoreLastPlayingSong: seekTo position={}ms", position.toMillis());
                    return this.handleAction(new PlayerAction.SeekTo(position)).thenApply(v -> songInfo);
                }, ASYNC_EXECUTOR)
                .thenAccept(songInfo -> log.info("restoreLastPlayingSong: restored songId={} title={}", songId, songInfo.title()))
                .exceptionally(throwable -> {
                    log.warn("Failed to restore last playing song: songId={}", songId, throwable);
                    return null;
                });
    }

    private AppState buildState() {
        return new AppState(Optional.empty(), this.player.getState(), this.playQueue.getState(), this.networkMonitor.getState());
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

    public java.util.List<DownloadQueueItem> getDownloadQueue() {
        return dbService.listDownloadQueue(java.util.List.of(
                DownloadQueueItem.DownloadStatus.COMPLETED,
                DownloadQueueItem.DownloadStatus.PENDING,
                DownloadQueueItem.DownloadStatus.DOWNLOADING,
                DownloadQueueItem.DownloadStatus.FAILED
        ));
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

    public void shutdown() {
        var start = System.currentTimeMillis();
        saveCurrentPlayerPreferencesImmediately();
        var saveTask = this.pendingPreferenceSave;
        if (saveTask != null) {
            saveTask.cancel(false);
        }
        this.preferenceSaveScheduler.shutdown();
        this.downloadManager.stop();
        this.scrobbleService.stop();
        var elapsed = System.currentTimeMillis() - start;
        log.info("AppManager shutdown completed in %dms".formatted(elapsed));
    }

    public Optional<Duration> getPlayerPosition() {
        return this.player.getCurrentPosition();
    }

    public SearchResultStore getSearchResultStore() {
        return this.searchResultStore;
    }

    public GSongStore getSongStore() {
        return this.gSongStore;
    }

    public record AlbumInfo(
            ServerClient.AlbumInfo album,
            List<GSongInfo> songs
    ){}
    public CompletableFuture<AlbumInfo> getAlbumInfoAsync(String albumId) {
        return Utils.doAsync(() -> {
            var data = this.useClient(serverClient -> serverClient.getAlbumInfo(albumId));

            var songs = data.songs().stream().map(gSongStore::newInstance).toList();
            return new AlbumInfo(data, songs);
        });
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
            PlayQueue.PlayQueueState queue,
            NetworkState networkState
    ) implements AppManagerAppStateBuilder.With {}

    public void enqueue(SongInfo songInfo) {
        this.playQueue.enqueue(songInfo);
    }


    public CompletableFuture<LoadSongResult> loadSource(PlayerAction.PlaySong songInfo) {
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

    private LoadSongResult loadSourceSync(PlayerAction.PlaySong playCmd) {
        var songInfo = playCmd.song();
        boolean startPaused = playCmd.startPaused();
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
                        songInfo.transcodeInfo().streamUri(),
                        Optional.of(Duration.ZERO),
                        Optional.of(songInfo.duration())
                ))))
                .build()
        );
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        LoadSongResult song = songCache.getSong(new CacheSong(
                SERVER_ID,
                songInfo.id(),
                songInfo.transcodeInfo(),
                songInfo.suffix(),
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
        boolean startPlaying = !startPaused;
        this.player.setSource(
                new AudioSource(song.uri(), songInfo.duration()),
                startPlaying
        );

        this.setState(old -> old.withNowPlaying(Optional.of(new NowPlaying(
                songInfo,
                READY,
                requestId,
                new BufferingProgress(1000, 1000),
                Optional.of(song)
        ))));

        // block after updating UI NowPlaying state
        if (!startPlaying) {
            // Block until GStreamer has prerolled (reached PAUSED), so subsequent seeks work.
            this.player.waitUntilReady();
        }
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
        saveCurrentPlayerPreferences();
    }

    public void unMute() {
        this.player.setMute(false);
        saveCurrentPlayerPreferences();
    }

    public void seekTo(Duration position) {
        this.player.seekTo(position);
    }

    public void setVolume(double linearVolume) {
        this.player.setVolume(linearVolume);
        saveCurrentPlayerPreferences();
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
                case PlayerAction.EnqueueLast a -> this.playQueue.enqueueLast(a.song());
                case PlayerAction.RemoveFromQueue a -> this.playQueue.removeAt(a.position());
                case PlayPositionInQueue a -> this.playQueue.playPosition(a.position());
                case PlayerAction.PlayAndReplaceQueue a -> this.playQueue.playAndReplaceQueue(a);
                case PlayerAction.Pause a -> this.pause();
                case PlayerAction.Play a -> this.play();
                case PlayerAction.PlayNext a -> this.playQueue.attemptPlayNext();
                case PlayerAction.PlayPrev a -> this.playQueue.attemptPlayPrev();
                case PlayerAction.SeekTo seekTo -> this.player.seekTo(seekTo.position());
                case PlayerAction.SetPlayMode a -> {
                    switch (a.mode()) {
                        case SHUFFLE -> this.playQueue.shuffle();
                        case NORMAL -> this.playQueue.unshuffle();
                        case REPEAT_ONE -> this.playQueue.setPlayMode(a.mode());
                    }
                }

                // API actions
                case PlayerAction.Star a -> this.starSong(a);
                case PlayerAction.Star2 a -> this.starSong(a);
                case PlayerAction.StarRefresh a -> this.starredList.handleRefresh(a);
                case PlayerAction.Unstar a -> this.unstarSong(a);
                case PlayerAction.PlaySong playSong -> this.loadSource(playSong);
                case PlayerAction.RefreshPlaylists _ -> this.playlistsStore.refreshListAsync();
                case PlayerAction.AddToPlaylist a -> {
                    this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), a.song().id())));
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added to " + a.playlistName())));
                }
                case PlayerAction.AddManyToPlaylist a -> {
                    Utils.doAsync(() -> {
                        for (var song : a.songs()) {
                            this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), song.getId())));
                        }
                    });
                    String msg = "Adding %d items to %s".formatted(a.songs().size(), a.playlistName());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(msg)));
                }
                case PlayerAction.AddToDownloadQueue a -> {
                    this.downloadManager.enqueue(a.song());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added to download queue")));
                }
                case PlayerAction.AddManyToDownloadQueue a -> {
                    for (var song : a.songs()) {
                        this.downloadManager.enqueue(song.getSongInfo());
                    }
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added %d items to download queue".formatted(a.songs().size()))));
                }
                case PlayerAction.OverrideNetworkStatus(var a) -> {
                    this.networkMonitor.setOverrideState(a);
                }
                case PlayerAction.SyncDatabase _ -> {
                    var syncService = new com.github.subsound.persistence.database.SyncService(
                            this.client.get(), this.dbService, UUID.fromString(SERVER_ID), this.thumbnailCache, this.songCache
                    );
                    var stats = syncService.syncAll();
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(
                            "Synced %d artists, %d albums, %d songs".formatted(
                                    stats.artists(), stats.albums(), stats.songs()
                            )
                    )));
                }
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
            this.setClient(newClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setClient(ServerClient newClient) {
        if (newClient != null) {
            var client = wrapWithCaching(newClient);
            this.client.set(client);
            this.starredList.refreshAsync();
        } else {
            this.client.set(null);
        }
    }

    private CachingClient wrapWithCaching(ServerClient raw) {
        var cachingClient = new CachingClient(
                raw, this.dbService, SERVER_ID, this.config.dataDir
        );
        cachingClient.setNetworkStatus(this.networkMonitor.getState().status());
        return cachingClient;
    }

    /**
     * Saves player preferences with debouncing (500ms delay).
     * If called multiple times within the delay, only the last call will actually save.
     */
    public void saveCurrentPlayerPreferences() {
        // Cancel any pending save
        var ref = pendingPreferenceSave;
        if (ref != null) {
            ref.cancel(false);
            pendingPreferenceSave = null;
        }
        // Schedule a new save after 500ms
        pendingPreferenceSave = preferenceSaveScheduler.schedule(
                this::saveCurrentPlayerPreferencesImmediately,
                2000,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Saves player preferences immediately without debouncing.
     * Use this for shutdown or when immediate save is required.
     */
    public void saveCurrentPlayerPreferencesImmediately() {
        // Cancel any pending debounced save
        var ref = pendingPreferenceSave;
        if (ref != null) {
            ref.cancel(false);
            pendingPreferenceSave = null;
        }
        var state = this.player.getState();
        // Convert linear volume to cubic for storage (setVolume expects cubic)
        double cubicVolume = PlaybinPlayer.toVolumeCubic(state.volume());
        var playerState = this.currentState.getValue();
        var currentSongId = playerState.nowPlaying()
                .map(NowPlaying::song)
                .map(SongInfo::id)
                .orElse(null);
        var currentSource = this.player.getState().source();
        var playerPosition = currentSource.flatMap(Source::position).orElse(Duration.ZERO);
        var playerDuration = currentSource.flatMap(Source::duration).orElse(Duration.ZERO);

        PlayerStateJson.PlaybackPosition playbackPosition = currentSongId != null
                ? new PlayerStateJson.PlaybackPosition(currentSongId, playerPosition.toMillis(), playerDuration.toMillis())
                : null;
        var playerConfig = new PlayerConfig(
                SERVER_ID,
                new PlayerStateJson(cubicVolume, state.muted(), playbackPosition),
                java.time.Instant.now()
        );
        try {
            this.playerConfigService.savePlayerConfig(playerConfig);
            log.info("saveCurrentPlayerPreferencesImmediately: saved player preferences: volume={} (linear={}), muted={}", cubicVolume, state.volume(), state.muted());
        } catch (Exception e) {
            log.error("failed to save player preferences", e);
        }
    }

    private void unstarSong(PlayerAction.Unstar a) {
        this.starredList.removeStarred(a.song());
        this.client.get().unStarId(a.song().id());
        this.playlistsStore.updateStarredCount(this.starredList.getStore().getNItems());
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

    private void starSong(PlayerAction.Star2 a) {
        starSong(a.song().getSongInfo());
    }

    private void starSong(PlayerAction.Star a) {
        starSong(a.song());

    }
    private void starSong(SongInfo song) {
        try {
            this.starredList.addStarred(song);
            this.client.get().starId(song.id());
        } catch (Exception e) {
            this.starredList.removeStarred(song);
            throw e;
        }
        this.playlistsStore.updateStarredCount(this.starredList.getStore().getNItems());
        setState(appState -> appState.nowPlaying()
                .map(nowPlaying -> {
                    var currentSong = nowPlaying.song();
                    if (currentSong.id().equals(song.id())) {
                        var updated = nowPlaying.withSong(currentSong.withStarred(Optional.ofNullable(Instant.now())));
                        return appState.withNowPlaying(Optional.of(updated));
                    } else {
                        return appState;
                    }
                }).orElse(appState));
    }

    public ListStore<GSongInfo> getStarredList() {
        return this.starredList.getStore();
    }

    public ListStore<PlaylistsStore.GPlaylist> getPlaylistsListStore() {
        return this.playlistsStore.playlistsListStore();
    }

    public ListStore<GQueueItem> getPlayQueueListStore() {
        return this.playQueue.getListStore();
    }

    private final Lock lock = new ReentrantLock();

    private void setState(Function<AppState, AppState> modifier) {
        try {
            lock.lock();
            var start = System.nanoTime();
            var nextState = modifier.apply(this.currentState.getValue());
            this.currentState.onNext(nextState);
            var elapsedNanos = System.nanoTime() - start;
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            if (elapsedMillis > 10) {
                log.warn("long running update took {}ms for state={}", elapsedMillis, nextState);
            }
        } finally {
            lock.unlock();
        }
    }

    //TODO: consider if this is better implemented in the PlayQueue
    private void checkScrobble(PlaybinPlayer.PlayerState playerState) {
        var nowPlaying = getState().nowPlaying();
        if (nowPlaying.isEmpty()) {
            this.scrobbledForRequestId = null;
            return;
        }
        var np = nowPlaying.get();
        var requestId = np.requestId();

        // Already scrobbled this playback instance
        if (requestId.equals(this.scrobbledForRequestId)) {
            return;
        }

        var source = playerState.source();
        if (source.isEmpty()) {
            return;
        }

        var dur = source.get().duration();
        if (dur.isEmpty()) {
            return;
        }
        long durMs = dur.get().toMillis();
        if (durMs <= 0) {
            return;
        }
        var now = System.currentTimeMillis();
        var playedStartedAt = playerState.playbackStartedAt().map(Instant::toEpochMilli).orElse(now);
        long posMs = now - playedStartedAt;

        boolean thresholdMet;
        if (durMs < 30_000) {
            // Short tracks: must play nearly the full track:
            thresholdMet = posMs >= (durMs * 0.95);
        } else {
            // Normal tracks: 50% of track or 4 minutes, whichever comes first
            thresholdMet = posMs >= durMs / 2 || posMs >= 4 * 60 * 1000;
        }

        if (thresholdMet) {
            scrobbledForRequestId = requestId;
            double durationSecond = durMs / 1000.0;
            double playedForSecond = posMs / 1000.0;
            double playedPercentage = playedForSecond / durationSecond;
            String playedPercentageStr = "%.1f".formatted(100 * playedPercentage);
            var songId = np.song().id();
            var title = np.song().title();
            Utils.doAsync(() -> {
                try {

                    dbService.insertScrobble(songId, Instant.ofEpochMilli(playedStartedAt));
                    scrobbleService.triggerSubmit();
                    log.info("Scrobble recorded for song: duration={}s playback={}s playedPercentage={} songId={} title={}", durationSecond, playedForSecond, playedPercentageStr, songId, title);
                } catch (Exception e) {
                    log.error("Failed to record scrobble for song: {}", songId, e);
                }
            });
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
