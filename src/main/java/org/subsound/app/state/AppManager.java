package org.subsound.app.state;

import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.ToastOverlay;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.NetworkMonitoring.NetworkState;
import org.subsound.app.state.PlayerAction.Enqueue;
import org.subsound.app.state.PlayerAction.PlayPositionInQueue;
import org.subsound.configuration.Config;
import org.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ObjectIdentifier;
import org.subsound.integration.ServerClient.PlaylistCreateRequest;
import org.subsound.integration.ServerClient.PlaylistDeleteRequest;
import org.subsound.integration.ServerClient.PlaylistRemoveSongRequest;
import org.subsound.integration.ServerClient.PlaylistRenameRequest;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.integration.playbackreport.PlaybackReporter;
import org.subsound.persistence.CachingClient;
import org.subsound.persistence.DownloadManager;
import org.subsound.persistence.ScrobbleService;
import org.subsound.persistence.SongCache;
import org.subsound.persistence.SongCache.LoadSongResult;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DatabaseService;
import org.subsound.persistence.database.DownloadQueueItem;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.subsound.persistence.database.PlayQueueItemRow;
import org.subsound.persistence.database.PlayQueueStateJson;
import org.subsound.persistence.database.PlayerConfig;
import org.subsound.persistence.database.PlayerConfigService;
import org.subsound.persistence.database.PlayerStateJson;
import org.subsound.persistence.database.Server;
import org.subsound.persistence.database.SyncService;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.sound.PlaybinPlayer.AudioSource;
import org.subsound.sound.PlaybinPlayer.Source;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.models.GQueueItem;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.utils.Utils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.subsound.app.state.AppManager.NowPlaying.State.LOADING;
import static org.subsound.app.state.AppManager.NowPlaying.State.READY;
import static org.subsound.i18n.I18n.tr;
import static org.subsound.i18n.I18n.trn;
import static org.subsound.utils.Utils.doAsync;
import static org.subsound.utils.Utils.runOnMainThread;
import static org.subsound.utils.Utils.timeIt;

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
    private final GSongStore gSongStore;
    private final StarredListStore starredList;
    private final PlaylistsStore playlistsStore;
    private final SearchResultStore searchResultStore;
    private final Database database;
    private final DatabaseService databaseService;
    private final DatabaseServerService dbService;
    private final PlayerConfigService playerConfigService;
    private final DownloadManager downloadManager;
    private final ScrobbleService scrobbleService;
    private final NetworkMonitoring networkMonitor;
    private final Runnable onQuit;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final PlaybackReporter playbackReporter;
    private volatile CompletableFuture<?> pendingPreferenceSave;
    // Scrobble session is bound to the actual song committed to the player (set after setSource),
    // not to AppState.nowPlaying — which gets updated earlier during song switches and caused
    // scrobbles to be recorded against the incoming song with the outgoing song's start time.
    private final AtomicReference<ScrobbleSession> scrobbleSession = new AtomicReference<>();
    // Snapshot of the last (session, playbackStartedAt, state) observed by the player listener.
    // A position tick leaves all three unchanged and short-circuits; real transitions trigger a
    // scrobble evaluation against the PREVIOUS observation — we need the outgoing startedAt
    // because setSource/seek reset the live value before the transition's listener fire lands.
    private final AtomicReference<Observation> lastObservation = new AtomicReference<>();
    private final AtomicInteger loadGeneration = new java.util.concurrent.atomic.AtomicInteger(0);

    private Optional<ApplicationWindow> window = Optional.empty();
    private ToastOverlay toastOverlay;
    private AppNavigation navigator;

    public AppManager(
            Config config,
            PlaybinPlayer player,
            ThumbnailCache thumbnailCache,
            Runnable onQuit
    ) {
        this.config = config;
        this.onQuit = onQuit;
        this.player = player;
        this.thumbnailCache = thumbnailCache;
        this.database = new Database();
        this.databaseService = new DatabaseService(this.database);
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
        // Load server configuration from DB (or migrate from legacy JSON)
        this.client = new AtomicReference<>();
        this.songCache = new SongCache(
                config.dataDir,
                transcodeInfo -> this.useClient(c -> c.openStream(transcodeInfo)),
                2
        );
        this.downloadManager = new DownloadManager(dbService, songCache, this.client::get);
        this.gSongStore = new GSongStore(
                songId -> this.useClient(c -> c.getSong(songId)),
                this.downloadManager
        );
        this.networkMonitor = new GioNetworkStatusMonitor(this::updateNetworkState);
        this.playQueue = new PlayQueue(
                player,
                this.gSongStore,
                nextState -> this.setState(old -> old.withQueue(nextState)),
                songInfo -> loadSourceAsync(new PlayerAction.PlaySong(songInfo.getSongInfo()))
        );

        this.thumbnailCache.setDownloader(
                (coverArt, maxSize) -> this.useClient(c -> c.downloadCoverArt(coverArt, maxSize))
        );
        initServerConfig(config);

        this.currentState = BehaviorSubject.createDefault(buildState());
        var disposable = this.currentState
                .throttleLatest(250, TimeUnit.MILLISECONDS, true)
                //.throttleLatest(50, TimeUnit.MILLISECONDS, true)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .forEach(next -> this.notifyListeners());

        // Register the player listener only after currentState exists: the listener calls
        // setState, and a GStreamer notification arriving before this point would NPE.
        player.onStateChanged(next -> {
            this.setState(old -> old.withPlayer(next));
            var sessionNow = this.scrobbleSession.get();
            long startedNow = next.playbackStartedAt().map(Instant::toEpochMilli).orElse(0L);
            var obs = new Observation(sessionNow, startedNow, next.state());
            var prev = this.lastObservation.getAndSet(obs);
            // Position ticks leave (session, startedAt, state) unchanged and are skipped here.
            // On real transitions, score the PREVIOUS observation — its startedAt is the outgoing
            // session's, which is the correct input for the threshold calc even after setSource
            // has since reset the player's live playbackStartedAt.
            // Only score observations that were PLAYING: since evaluateScrobble measures
            // wall-clock time from startedAt to "now", scoring e.g. a PAUSED observation
            // would count the entire pause as listening time (pause 10s in, wait 5 min,
            // skip => false scrobble). Anything listened up to a non-PLAYING observation
            // was already scored when that observation replaced its PLAYING predecessor.
            if (prev != null && !prev.equals(obs) && prev.state() == PlaybinPlayer.PlayerStates.PLAYING) {
                this.evaluateScrobble(prev.session(), prev.playbackStartedAtMs());
            }
        });

        // restore saved volume:
        this.player.setVolume(savedPlayerState.volume());
        this.player.setMute(savedPlayerState.muted());

        // Restore play queue from DB
        this.restorePlayQueue();

        // Restore last playing song from DB (without auto-playing)
        var lastPlayback = savedPlayerState.currentPlayback();
        if (lastPlayback != null && lastPlayback.songId() != null && !lastPlayback.songId().isBlank()) {
            restoreLastPlayingSong(lastPlayback);
        }

        this.starredList = new StarredListStore(this, dbService, UUID.fromString(savedServerId));
        this.playlistsStore = new PlaylistsStore(this);
        this.searchResultStore = new SearchResultStore(this.client::get);

        // Refresh "Downloaded" playlist counts whenever any download state changes.
        this.downloadManager.subscribe(_ ->
                this.playlistsStore.updateDownloadedCounts(this.downloadManager.getDownloadCounts())
        );

        if (this.client.get() != null) {
            this.starredList.refreshAsync();
            this.playlistsStore.refreshListAsync();
        }
        this.scrobbleService = new ScrobbleService(
                dbService,
                () -> this.client.get(),
                () -> this.getState().networkState()
        );
        this.playbackReporter = new PlaybackReporter(this.client::get);
        this.addOnStateChanged(this.playbackReporter);
    }

    private void initServerConfig(Config config) {
        if (config.serverId == null) {
            return; // No server configured — onboarding needed
        }

        var secretService = config.getSecretService();
        var serverOpt = this.databaseService.getServerById(config.serverId);

        if (serverOpt.isPresent()) {
            // Server found in DB — build config from it
            var server = serverOpt.get();
            var password = resolvePassword(secretService, server.id().toString(), server.username(), config);
            config.serverConfig = buildServerConfig(config.dataDir, server, password);
            var serverClient = ServerClient.create(config.serverConfig);
            this.client.set(wrapWithCaching(serverClient));
        } else if (config.legacyServerConfig != null) {
            // Legacy JSON config — migrate to DB
            log.info("Migrating legacy server config to database for serverId={}", config.serverId);
            var legacy = config.legacyServerConfig;
            var password = resolvePassword(secretService, legacy.id(), legacy.username(), config);
            // Also check legacy password from JSON (may be plain-text in old format)
            if (password == null && legacy.password() != null && !legacy.password().isBlank()) {
                password = legacy.password();
                // Migrate to keyring
                boolean stored = secretService.storeCredentialsSync(legacy.id(), legacy.username(), password);
                if (stored) {
                    log.info("Migrated legacy password to keyring for server={}", legacy.id());
                }
            }

            var server = new Server(
                    UUID.fromString(legacy.id()),
                    true,
                    legacy.type(),
                    legacy.url(),
                    legacy.username(),
                    Instant.now(),
                    false,
                    legacy.audioFormat(),
                    legacy.transcodeBitrate(),
                    List.of()
            );
            this.databaseService.insert(server);
            config.legacyServerConfig = null;

            // Re-save JSON in new format (strips legacy server fields)
            try {
                config.saveToFile();
                log.info("Saved config in new format (server details moved to database)");
            } catch (IOException e) {
                log.warn("Failed to re-save config after migration", e);
            }

            config.serverConfig = buildServerConfig(config.dataDir, server, password);
            var serverClient = ServerClient.create(config.serverConfig);
            this.client.set(wrapWithCaching(serverClient));
        } else {
            log.warn("serverId={} set in config but no server found in database", config.serverId);
        }
    }

    private static @org.jspecify.annotations.Nullable String resolvePassword(
            org.subsound.integration.platform.secret.SecretService secretService,
            String serverId,
            String username,
            Config config
    ) {
        var creds = secretService.lookupCredentialsSync(serverId, username);
        if (creds != null) {
            return creds.password();
        }
        // Fallback: password stored in config file when keyring is unavailable (e.g. macOS)
        return config.fallbackPassword;
    }

    private static Config.ServerConfig buildServerConfig(Path dataDir, Server server, @org.jspecify.annotations.Nullable String password) {
        TranscodeFormat audioFormat = null;
        if (server.audioFormat() != null) {
            try {
                audioFormat = TranscodeFormat.valueOf(server.audioFormat());
            } catch (IllegalArgumentException ignored) {}
        }
        ServerClient.TranscodeBitrate audioBitrate = null;
        if (server.audioBitrate() != null && server.audioBitrate() > 0) {
            audioBitrate = ServerClient.TranscodeBitrate.MaximumBitrate.of(server.audioBitrate());
        }
        return new Config.ServerConfig(
                dataDir,
                server.id().toString(),
                server.serverType(),
                server.serverUrl(),
                server.username(),
                password != null ? password : "",
                audioFormat,
                audioBitrate,
                server.tlsSkipVerify(),
                server.customHeaders()
        );
    }

    private void updateNetworkState(Void unused) {
        var next = this.networkMonitor.getState();
        this.setState(appState -> appState.withNetworkState(next));

        // Update CachingClient with network status
        var client = this.client.get();
        if (client != null) {
            client.setNetworkStatus(next.status());
        }
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
                    throw new RuntimeException("Failed to restore last playing song: serverClient is null");
                }
                return serverClient.getSong(songId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to restore last playing song: songId=%s".formatted(songId), e);
            }
        }, ASYNC_EXECUTOR)
                .thenComposeAsync(songInfo -> this.loadSourceAsync(new PlayerAction.PlaySong(songInfo, true)).thenApply(v -> songInfo),
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
        var initialServerId = this.config.serverConfig != null
                ? ServerState.of(this.config.serverConfig.id())
                : ServerState.empty();
        return new AppState(Optional.empty(), this.player.getState(), this.playQueue.getState(), this.networkMonitor.getState(), initialServerId);
    }

    public AppState getState() {
        return this.currentState.getValue();
    }

    public ThumbnailCache getThumbnailCache() {
        return thumbnailCache;
    }

    public <T> T useClient(Function<ServerClient, T> useFunc) {
        var c = this.client.get();
        if (c == null) {
            throw new IllegalStateException("No server client configured");
        }
        return useFunc.apply(c);
    }
    public void useClient1(Consumer<ServerClient> useFunc) {
        var c = this.client.get();
        if (c == null) {
            throw new IllegalStateException("No server client configured");
        }
        useFunc.accept(c);
    }

    public Config getConfig() {
        return config;
    }

    public DownloadManager.DownloadCounts getDownloadCounts() {
        return this.downloadManager.getDownloadCounts();
    }

    public long getLocalSongCount() {
        return dbService.getSongCount();
    }

    public java.util.List<DownloadQueueItem> getDownloadQueue() {
        return dbService.listDownloadQueue(java.util.List.of(
                DownloadStatus.COMPLETED,
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
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
        if (!this.isShutdown.compareAndSet(false, true)) {
            return;
        }
        // Catch a threshold-met scrobble on clean quit: no further transitions will fire.
        // Only while PLAYING — when paused/stopped, everything listened was already scored
        // at the transition out of PLAYING, and the wall-clock measurement in
        // evaluateScrobble would count the pause as listening time.
        var playerStateAtShutdown = this.player.getState();
        if (playerStateAtShutdown.state() == PlaybinPlayer.PlayerStates.PLAYING) {
            this.evaluateScrobble(
                    this.scrobbleSession.get(),
                    playerStateAtShutdown.playbackStartedAt().map(Instant::toEpochMilli).orElse(0L)
            );
        }
        this.removeOnStateChanged(this.playbackReporter);
        this.playbackReporter.close();
        var start = System.currentTimeMillis();
        timeIt(
                duration -> log.info("shutdown: saveCurrentPlayerPreferencesImmediately: {}ms", duration.toMillis()),
                this::saveCurrentPlayerPreferencesImmediately
        );
        timeIt(
                duration -> log.info("shutdown: savePlayQueueImmediately: {}ms", duration.toMillis()),
                this::savePlayQueueImmediately
        );

        var saveTask = this.pendingPreferenceSave;
        if (saveTask != null) {
            saveTask.cancel(true);
        }
        timeIt(
                duration -> log.info("shutdown: downloadManager: {}ms", duration.toMillis()),
                this.downloadManager::stop
        );
        timeIt(
                duration -> log.info("shutdown: scrobbleService: {}ms", duration.toMillis()),
                this.scrobbleService::stop
        );
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

    public List<ServerClient.SongInfo> listDownloadedSongInfos() {
        var cl = this.client.get();
        if (cl == null) {
            log.warn("listDownloadedSongInfos: client is null, returning empty list");
            return List.of();
        }
        var dbSongs = this.downloadManager.listDownloadedDBSongs();
        var result = dbSongs.stream()
                .map(cl::dbSongToSongInfo)
                .toList();
        log.info("listDownloadedSongInfos: dbSongs={} mapped={}", dbSongs.size(), result.size());
        return result;
    }

    public record AlbumInfo(
            ServerClient.AlbumInfo album,
            List<GSongInfo> songs
    ){}
    public CompletableFuture<AlbumInfo> getAlbumInfoAsync(String albumId) {
        return doAsync(() -> {
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

    public record ServerState(Optional<String> serverId) {
        public static ServerState empty() {
            return new ServerState(Optional.empty());
        }
        public static ServerState of(String id) {
            return new ServerState(Optional.of(id));
        }
    }

    @RecordBuilderFull
    public record AppState(
            Optional<NowPlaying> nowPlaying,
            PlaybinPlayer.PlayerState player,
            PlayQueue.PlayQueueState queue,
            NetworkState networkState,
            ServerState serverState
    ) implements AppManagerAppStateBuilder.With {}

    public void enqueue(SongInfo songInfo) {
        this.playQueue.enqueue(songInfo);
    }


    public CompletableFuture<LoadSongResult> loadSourceAsync(PlayerAction.PlaySong songInfo) {
        int myGeneration = loadGeneration.incrementAndGet();
        UUID requestId = UUID.randomUUID();
        // Eagerly mark the new song as loading, before the async hop and the network work
        // in loadSourceSyncInner, so the UI (row highlight, player bar) switches to the new
        // song immediately instead of after the stream URI has resolved. The queue state is
        // snapshotted in the same setState so nowPlaying.song.id and queue.playingItemId
        // land atomically — a mismatched pair would clear the highlight in the views.
        // The snapshot is taken outside the modifier to keep lock ordering
        // PlayQueue -> AppManager, same as the PlayQueue.notifyState path.
        var song = songInfo.song();
        var queueState = this.playQueue.getState();
        this.setState(old -> old.with()
                .nowPlaying(Optional.of(new NowPlaying(
                        song,
                        LOADING,
                        requestId,
                        new BufferingProgress(song.size(), 0),
                        Optional.empty()
                )))
                .queue(queueState)
                .build()
        );
        return CompletableFuture.supplyAsync(
                () -> this.loadSourceSync(songInfo, requestId),
                ASYNC_EXECUTOR
        ).handle((result, throwable) -> {
            boolean isCurrentLoad = loadGeneration.get() == myGeneration;
            // In REPEAT_ONE, attemptPlayNext replays the same song, which would retry the
            // same failing load forever — don't auto-advance past a failed song then.
            boolean skipAhead = isCurrentLoad
                                && this.playQueue.getState().playMode() != PlayerAction.PlayMode.REPEAT_ONE;
            if (throwable != null) {
                log.error("loadSourceAsync: failed to load: {} {}", songInfo.song().id(), songInfo.song().title(), throwable);
                if (skipAhead) {
                    this.playQueue.attemptPlayNext();
                }
                return null;
            }
            if (result == null && skipAhead) {
                // loadSourceSync returned null (song not available); toast already shown
                this.playQueue.attemptPlayNext();
            }
            return result;
        });
    }

    public interface ProgressHandler {
        interface ProgressUpdate {}
        record Start(SongInfo songInfo) implements ProgressUpdate {}
        record Update(SongInfo songInfo, float percent) implements ProgressUpdate {}
        record Completed(SongInfo songInfo) implements ProgressUpdate {}

        void update(ProgressUpdate u);
    }

    private LoadSongResult loadSourceSync(PlayerAction.PlaySong playCmd, UUID requestId) {
        var songInfo = playCmd.song();
        try {
            var result = loadSourceSyncInner(songInfo, playCmd.startPaused(), requestId);
            if (result == null) {
                // Song was not available (e.g. offline and not cached)
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to load song: id={} title={}", playCmd.song().id(), playCmd.song().title(), e);
            this.abortFailedLoad(requestId);
            this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Failed to load song"))));
            throw e;
        }
    }

    /**
     * Cleanup after a failed load: unload the stale track from GStreamer so a later
     * Play cannot resume the previous song while the UI shows a different one, and
     * clear nowPlaying. Guarded on requestId so a newer load that has already taken
     * over the player state is left untouched.
     */
    private void abortFailedLoad(UUID requestId) {
        // Decide ownership atomically inside setState (serialized with the eager setState of
        // any newer load), so a check-then-unload race cannot tear down a newer load's state.
        var owned = new AtomicBoolean(false);
        this.setState(old -> {
            var np = old.nowPlaying();
            if (np.isEmpty() || !np.get().requestId().equals(requestId)) {
                // a newer load owns the state now
                return old;
            }
            owned.set(true);
            return old.withNowPlaying(Optional.empty());
        });
        if (!owned.get()) {
            return;
        }
        this.player.unloadSource();
        this.scrobbleSession.set(null);
    }

    private LoadSongResult loadSourceSyncInner(SongInfo songInfo, boolean startPaused, UUID requestId) {
        this.pause();
        // resolve the songUri:
        // - authentication in the url may have been changed since it was generated
        // - transcode settings my have changed since it was generated
        var songUri = resolveStreamUri(songInfo);

        // If server is unreachable and song is not cached, we can't play it
        if (songUri.isEmpty()) {
            if (!this.downloadManager.isAvailableOffline(songInfo)) {
                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Song not available offline"))));
                this.abortFailedLoad(requestId);
                return null;
            }
        }

        this.setState(old -> old.with()
                .nowPlaying(Optional.of(new NowPlaying(
                        songInfo,
                        LOADING,
                        requestId,
                        new BufferingProgress(songInfo.size(), 0),
                        Optional.empty()
                )))
                .player(songUri
                        .map(uri -> old.player.withSource(Optional.of(new Source(
                                uri,
                                Optional.of(Duration.ZERO),
                                Optional.of(songInfo.duration())
                        ))))
                        .orElse(old.player)
                )
                .build()
        );
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        LoadSongResult cachedSong = downloadManager.loadForPlayback(
                songInfo,
                (total, count) -> {
                    if (isCancelled.get()) {
                        throw new SongCache.CancelledDownloadException();
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
                    if (isCancelled.get()) {
                        throw new SongCache.CancelledDownloadException();
                    }
                }
        );
        log.info("cached: result={} id={} title={}", cachedSong.result().name(), songInfo.id(), songInfo.title());
        if (cachedSong.result() == SongCache.CacheResult.CANCELLED) {
            return null;
        }
        AppState appState = this.currentState.getValue();
        var currentRequestId = appState.nowPlaying().map(NowPlaying::requestId).orElse(null);
        if (!requestId.equals(currentRequestId)) {
            // we changed song while loading. Ignore this and do nothing:
            return cachedSong;
        }
        boolean startPlaying = !startPaused;
        this.player.setSource(
                new AudioSource(cachedSong.uri(), songInfo.duration()),
                startPlaying
        );
        // Commit the scrobble session for this song. setSource has already reset
        // playbackStartedAt to 0; the next PLAYING transition will set it to "now",
        // which is what we want threshold calculations to measure against.
        this.scrobbleSession.set(new ScrobbleSession(
                songInfo.id(),
                requestId,
                songInfo.duration().toMillis(),
                false
        ));
        Utils.doAsync(this::prefetchNextSong);
        Utils.doAsync(() -> prefetchLyrics(songInfo));

        this.setState(old -> old.withNowPlaying(Optional.of(new NowPlaying(
                songInfo,
                READY,
                requestId,
                new BufferingProgress(1000, 1000),
                Optional.of(cachedSong)
        ))));

        // block after updating UI NowPlaying state
        if (!startPlaying) {
            // Block until GStreamer has prerolled (reached PAUSED), so subsequent seeks work.
            this.player.waitUntilReady();
        }
        return cachedSong;
    }

    private void prefetchNextSong() {
        var next = this.playQueue.peekNext();
        if (next.isEmpty()) {
            return;
        }
        var songInfo = next.get().getSongInfo();
        Utils.doAsync(() -> {
            var result = this.downloadManager.loadForPlayback(songInfo, (total, count) -> {});
            if (result.result() != SongCache.CacheResult.CANCELLED) {
                log.debug("prefetchNextSong: cached song={}", songInfo.id());
            }
            return null;
        });
    }

    private void prefetchLyrics(SongInfo songInfo) {
        try {
            var client = this.client.get();
            if (client == null) {
                return;
            }
            // warms the CachingClient in-flight future + lyrics DB row; result intentionally unused
            client.getSongLyrics(songInfo.id());
        } catch (Exception e) {
            log.warn("prefetchLyrics failed for songId={}: {}", songInfo.id(), e.getMessage());
        }
    }

    private Optional<URI> resolveStreamUri(SongInfo songInfo) {
        try {
            return Optional.of(this.useClient(client -> client.getStreamUri(songInfo.id())));
        } catch (Exception e) {
            log.warn("Failed to resolve stream URI for song={}", songInfo.id());
            return Optional.empty();
        }
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
        return doAsync(() -> {
            log.info("handleAction: payload={}", action);
            switch (action) {
                // config actions:
                case PlayerAction.SaveConfig settings -> this.saveConfig(settings);
                case PlayerAction.SaveTranscodeFormat a -> this.saveTranscodeFormat(a);
                case PlayerAction.Toast t -> this.toast(t);

                // player actions:
                case Enqueue a -> this.playQueue.enqueue(a.song());
                case PlayerAction.EnqueueLast a -> this.playQueue.enqueueLast(a.song());
                case PlayerAction.RemoveFromQueue a -> this.playQueue.removeAt(a.position());
                case PlayPositionInQueue a -> this.playQueue.playPosition(a.position());
                // join so the returned handleAction future completes after the queue swap:
                case PlayerAction.PlayAndReplaceQueue a -> this.playQueue.playAndReplaceQueue(a).join();
                case PlayerAction.Pause a -> this.pause();
                case PlayerAction.Play a -> this.play();
                case PlayerAction.PlayNext a -> this.playQueue.attemptPlayNext();
                case PlayerAction.PlayPrev a -> this.playQueue.attemptPlayPrev();
                case PlayerAction.SeekTo seekTo -> this.player.seekTo(seekTo.position());
                case PlayerAction.SeekRelative seek -> this.player.seekRelative(seek.offset());
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
                case PlayerAction.PlaySong playSong -> this.loadSourceAsync(playSong);
                case PlayerAction.RefreshPlaylists _ -> this.playlistsStore.refreshListAsync();
                case PlayerAction.AddToPlaylist a -> {
                    this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), List.of(a.song().id()))));
                    this.playlistsStore.refreshPlaylistAsync(a.playlistId());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Added to %s").formatted(a.playlistName()))));
                }
                case PlayerAction.AddManyToPlaylist a -> {
                    doAsync(() -> {
                        var songsIds = a.songs().stream().map(GSongInfo::getId).toList();
                        this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), songsIds)));
                        this.playlistsStore.refreshPlaylistAsync(a.playlistId());
                    });
                    String msg = trn("Adding %1$d item to %2$s", "Adding %1$d items to %2$s", a.songs().size())
                            .formatted(a.songs().size(), a.playlistName());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(msg)));
                }
                case PlayerAction.RemoveFromPlaylist a -> {
                    if (PlaylistsStore.DOWNLOADED_ID.equals(a.playlistId())) {
                        this.downloadManager.removeFromQueue(a.song().id());
                        break;
                    }
                    this.useClient1(c -> c.playlistRemove(new PlaylistRemoveSongRequest(
                            a.playlistId(),
                            List.of(new ServerClient.SongRemoval(a.originalPosition(), a.song().id()))
                    )));
                    //this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Removed from " + a.playlistName())));
                }
                case PlayerAction.AddToDownloadQueue a -> {
                    this.downloadManager.enqueue(a.song());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Added to download queue"))));
                }
                case PlayerAction.CreatePlaylist c -> {
                    var songs = c.songs().stream().map(GSongInfo::getId).toList();
                    var createdPlaylist = this.useClient(client -> client.playlistCreate(new PlaylistCreateRequest(
                            c.playlistName(),
                            songs
                    )));
                    this.playlistsStore.addNewPlaylist(createdPlaylist);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Created playlist %s").formatted(c.playlistName()))));
                }
                case PlayerAction.DeletePlaylist d -> {
                    this.useClient1(client -> client.playlistDelete(new PlaylistDeleteRequest(d.playlistId())));
                    this.playlistsStore.removePlaylist(d.playlistId());
                }
                case PlayerAction.RenamePlaylist r -> {
                    // unconditionally rename the playlist in the UI first, we dont really care if we fail to rename at server
                    this.playlistsStore.renamePlaylist(r.playlistId(), r.newName());
                    this.useClient1(client -> client.playlistRename(new PlaylistRenameRequest(r.playlistId(), r.newName())));
                }
                case PlayerAction.AddManyToDownloadQueue a -> {
                    for (var song : a.songs()) {
                        this.downloadManager.enqueue(song.getSongInfo());
                    }
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(
                            trn("Added %d item to download queue", "Added %d items to download queue", a.songs().size())
                                    .formatted(a.songs().size()))));
                }
                case PlayerAction.OverrideNetworkStatus(var a) -> {
                    this.networkMonitor.setOverrideState(a);
                }
                case PlayerAction.SyncDatabase _ -> {
                    var syncService = new SyncService(
                            this.client.get(), this.dbService, UUID.fromString(SERVER_ID), this.thumbnailCache, this.songCache
                    );
                    var stats = syncService.syncAll();
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(
                            tr("Synced %1$d artists, %2$d albums, %3$d songs").formatted(
                                    stats.artists(), stats.albums(), stats.songs()
                            )
                    )));
                }
                case PlayerAction.ClearSongCache _ -> {
                    this.downloadManager.resetSongCache();
                    this.songCache.clearSongs(SERVER_ID);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Song cache cleared"))));
                }
                case PlayerAction.ClearThumbnailCache _ -> {
                    this.thumbnailCache.clearThumbnails(SERVER_ID);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Thumbnail cache cleared"))));
                }
                case PlayerAction.ClearLyricsCache _ -> {
                    this.dbService.clearLyrics();
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Lyrics cache cleared"))));
                }
                case PlayerAction.TriggerServerScan arg -> {
                    Utils.doAsync(() -> this.useClient(c -> c.startScan(arg.quickScan())))
                            .thenApply(status -> {
                                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Started server scan"))));
                                return status;
                            })
                            .exceptionally(throwable -> {
                                log.error("Failed to start server scan", throwable);
                                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(tr("Failed to start server: %s").formatted(throwable.getMessage()))));
                                return null;
                            });
                }
                case PlayerAction.Quit _ -> {
                    this.player.pause();
                    this.shutdown();
                    this.onQuit.run();
                }
                case PlayerAction.Logout _ -> {
                    var serverId = Optional.ofNullable(this.config.serverConfig)
                            .map(Config.ServerConfig::id).orElse("");
                    player.pause();
                    shutdown();
                    database.close();
                    if (!serverId.isBlank()) {
                        this.config.getSecretService().deleteCredentials(serverId);
                    }
                    // Reset config state before deleting files.
                    // The onShutdown callback in Main.java may re-save the config file,
                    // so we need the saved state to be clean (triggers onboarding on next start).
                    this.config.onboarding = null;
                    this.config.serverId = null;
                    this.config.serverConfig = null;
                    this.config.credentialsInKeyring = false;
                    this.config.fallbackPassword = null;
                    try {
                        Files.deleteIfExists(config.getConfigFilePath());
                    } catch (Exception e) {
                        log.warn("delete config failed", e);
                    }
                    var dataDir = config.dataDir;
                    // be careful before calling delete on a dir:
                    if (dataDir != null && dataDir.toAbsolutePath().toString().toLowerCase().contains("subsound")) {
                        deleteDirectoryRecursively(dataDir);
                    }
                    onQuit.run();
                }
                case PlayerAction.RaiseWindow _ -> Utils.runOnMainThread(() -> this.window.ifPresent(ApplicationWindow::present));
            }
        });
    }

    private static void deleteDirectoryRecursively(java.nio.file.Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (Exception e) { log.warn("Failed to delete {}", p, e); }
                    });
        } catch (Exception e) {
            log.warn("Failed to walk dir for deletion: {}", dir, e);
        }
    }

    private void toast(PlayerAction.Toast t) {
        if (this.toastOverlay == null) {
            log.warn("unable to toast because toastOverlay={}", this.toastOverlay);
            return;
        }
        t.toast().setTimeout((int) t.timeout().toSeconds());
        runOnMainThread(() -> {
            this.toastOverlay.addToast(t.toast());
        });
    }

    private void saveConfig(PlayerAction.SaveConfig settings) {
        this.config.onboarding = OnboardingState.DONE;
        this.config.serverId = SERVER_ID;

        // Preserve existing audio settings
        String existingAudioFormat = null;
        Integer existingAudioBitrate = null;
        var existingServer = this.databaseService.getServerById(SERVER_ID);
        if (existingServer.isPresent()) {
            existingAudioFormat = existingServer.get().audioFormat();
            existingAudioBitrate = existingServer.get().audioBitrate();
        }

        // Upsert full server record to DB
        var server = new Server(
                UUID.fromString(SERVER_ID),
                true,
                settings.next().type(),
                settings.next().serverUrl(),
                settings.next().username(),
                existingServer.map(Server::createdAt).orElse(Instant.now()),
                settings.next().tlsSkipVerify(),
                existingAudioFormat,
                existingAudioBitrate,
                settings.next().customHeaders()
        );
        this.databaseService.upsert(server);

        // Clear any stale credentials for this server before storing new ones.
        // On Flatpak, the secret portal may accumulate entries if passwordStoreSync
        // does not overwrite when attributes differ (e.g. different username).
        var secretService = this.config.getSecretService();
        secretService.deleteCredentials(SERVER_ID);
        boolean stored = secretService.storeCredentialsSync(
                SERVER_ID,
                settings.next().username(),
                settings.next().password()
        );
        this.config.credentialsInKeyring = stored;

        // Build runtime config from DB + password
        this.config.serverConfig = buildServerConfig(
                this.config.dataDir, server, settings.next().password()
        );

        try {
            this.config.saveToFile();
            var newClient = ServerClient.create(this.config.serverConfig);
            this.setClient(newClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveTranscodeFormat(PlayerAction.SaveTranscodeFormat action) {
        if (this.config.serverConfig == null) {
            return;
        }

        // Load current server from DB, update audio fields
        var serverOpt = this.databaseService.getServerById(this.config.serverConfig.id());
        if (serverOpt.isEmpty()) {
            return;
        }
        var server = serverOpt.get();
        var format = action.audioFormat().format();
        var bitrate = action.audioFormat().bitrate();
        String audioFormatStr = format != null ? format.name() : null;
        Integer audioBitrateInt = switch (bitrate) {
            case null -> null;
            case ServerClient.TranscodeBitrate.SourceQuality _ -> null;
            case ServerClient.TranscodeBitrate.MaximumBitrate(var kbps) -> kbps;
        };

        var updatedServer = new Server(
                server.id(), server.isPrimary(), server.serverType(),
                server.serverUrl(), server.username(), server.createdAt(),
                server.tlsSkipVerify(), audioFormatStr, audioBitrateInt,
                server.customHeaders()
        );
        this.databaseService.upsert(updatedServer);

        // Rebuild runtime config
        this.config.serverConfig = buildServerConfig(
                this.config.dataDir, updatedServer, this.config.serverConfig.password()
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
            this.playlistsStore.refreshListAsync();
            var newServerId = this.config.serverConfig != null
                    ? ServerState.of(this.config.serverConfig.id())
                    : ServerState.empty();
            this.setState(old -> old.withServerState(newServerId));
        } else {
            this.client.set(null);
        }
    }

    private CachingClient wrapWithCaching(ServerClient raw) {
        // Do not force-sync the network status at creation: CachingClient defaults to ONLINE
        // so the server is tried first. The network monitor will update it once it has
        // queried the real connectivity (which in flatpak can take ~500 ms via the portal).
        var client = new CachingClient(
                raw,
                this.dbService,
                SERVER_ID,
                this.config.dataDir,
                this.downloadManager::getSongStatus
        );
        // Async DNS pre-check: if the server hostname can't be resolved within 5 seconds,
        // flip to offline immediately so feign requests don't block on the OS DNS timeout (~30s on macOS).
        if (this.config.serverConfig != null) {
            var host = URI.create(this.config.serverConfig.url()).getHost();
            CompletableFuture.runAsync(() -> {
                try {
                    java.net.InetAddress.getByName(host);
                } catch (Exception e) {
                    log.info("DNS pre-check: host {} not resolvable", host);
                    client.setNetworkStatus(NetworkMonitoring.NetworkStatus.OFFLINE);
                }
            }).orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> {
                log.info("DNS pre-check: timed out resolving {}, switching to offline", host);
                client.setNetworkStatus(NetworkMonitoring.NetworkStatus.OFFLINE);
                return null;
            });
        }
        return client;
    }

    /**
     * Saves player preferences with debouncing (2s delay).
     * If called multiple times within the delay, only the last call will actually save.
     */
    public void saveCurrentPlayerPreferences() {
        // Cancel any pending save
        var ref = pendingPreferenceSave;
        if (ref != null) {
            ref.cancel(true);
            pendingPreferenceSave = null;
        }
        // Schedule a new save after 2s on a virtual thread
        pendingPreferenceSave = CompletableFuture.runAsync(
                this::saveCurrentPlayerPreferencesImmediately,
                CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS, ASYNC_EXECUTOR)
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
            ref.cancel(true);
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
        // Use the live extrapolated position: PlayerState.source().position() is only refreshed
        // on discrete events (seek/pause/EOS) and is stale while PLAYING — e.g. on window-close
        // quit, where the pipeline goes straight to NULL without a position refresh.
        var playerPosition = this.player.getCurrentPosition().orElse(Duration.ZERO);
        var playerDuration = currentSource.flatMap(Source::duration).orElse(Duration.ZERO);

        String playContextType = null;
        String playContextId = null;
        var queueState = this.playQueue.getState();
        if (queueState.playContext().isPresent()) {
            var ctx = queueState.playContext().get();
            playContextId = ctx.getId();
            playContextType = serializeContextType(ctx);
        }

        PlayerStateJson.PlaybackPosition playbackPosition = currentSongId != null
                ? new PlayerStateJson.PlaybackPosition(currentSongId, playerPosition.toMillis(), playerDuration.toMillis(), playContextType, playContextId)
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

    private void savePlayQueueImmediately() {
        try {
            var queueState = this.playQueue.getState();

            // Save queue metadata
            String playContextType = null;
            String playContextId = null;
            if (queueState.playContext().isPresent()) {
                var ctx = queueState.playContext().get();
                playContextId = ctx.getId();
                playContextType = serializeContextType(ctx);
            }
            var metaJson = new PlayQueueStateJson(
                    queueState.position().orElse(null),
                    queueState.playMode().name(),
                    playContextType,
                    playContextId
            );
            this.playerConfigService.saveQueueState(metaJson);

            // Save queue items
            var listStore = this.playQueue.getListStore();
            var items = new ArrayList<PlayQueueItemRow>();
            for (int i = 0; i < listStore.getNItems(); i++) {
                var item = listStore.getItem(i);
                items.add(new PlayQueueItemRow(
                        SERVER_ID,
                        i,
                        item.getId(),
                        item.getQueueItemId(),
                        item.queueKind().name(),
                        item.getOriginalOrder(),
                        item.getShuffleOrder(),
                        null
                ));
            }
            this.dbService.savePlayQueueItems(items);
            log.info("savePlayQueueImmediately: saved {} queue items, position={}, playMode={}",
                    items.size(), queueState.position().orElse(-1), queueState.playMode());
        } catch (Exception e) {
            log.error("Failed to save play queue", e);
        }
    }

    private void restorePlayQueue() {
        var start = System.currentTimeMillis();
        int count = 0;
        try {
            var queueMeta = this.playerConfigService.loadQueueState()
                    .orElse(PlayQueueStateJson.empty());
            var savedItems = this.dbService.loadPlayQueueItems();

            if (savedItems.isEmpty()) {
                log.info("restorePlayQueue: no saved queue items");
                return;
            }

            var gqueueItems = new ArrayList<GQueueItem>();
            int skippedBeforePosition = 0;
            int savedPosition = queueMeta.position() != null ? queueMeta.position() : -1;
            var client = this.client.get();

            for (int i = 0; i < savedItems.size(); i++) {
                var row = savedItems.get(i);
                if (row.song() == null) {
                    log.info("restorePlayQueue: dropping song not found in database: {}", row.songId());
                    if (i < savedPosition) {
                        skippedBeforePosition++;
                    }
                    continue;
                }
                var songInfo = client.dbSongToSongInfo(row.song());
                var gSong = this.gSongStore.newInstance(songInfo);
                var queueKind = GQueueItem.QueueKind.valueOf(row.queueKind());
                var gItem = GQueueItem.newInstance(
                        row.queueItemId(),
                        gSong,
                        queueKind,
                        row.originalOrder()
                );
                gItem.setShuffleOrder(row.shuffleOrder());
                gqueueItems.add(gItem);
            }

            if (gqueueItems.isEmpty()) {
                log.info("restorePlayQueue: all songs were missing, nothing to restore");
                return;
            }

            int totalSkipped = skippedBeforePosition;
            Optional<Integer> position = Optional.ofNullable(queueMeta.position())
                    .map(p -> p - totalSkipped)
                    .filter(p -> p >= 0 && p < gqueueItems.size());

            PlayerAction.PlayMode playMode;
            try {
                playMode = PlayerAction.PlayMode.valueOf(queueMeta.playMode());
            } catch (Exception e) {
                playMode = PlayerAction.PlayMode.NORMAL;
            }

            Optional<ObjectIdentifier> playContext = deserializeContext(
                    queueMeta.playContextType(), queueMeta.playContextId()
            );
            count = gqueueItems.size();

            this.playQueue.restoreQueue(gqueueItems, position, playMode, playContext);
            log.info("restorePlayQueue: restored {} items, position={}, playMode={}",
                    gqueueItems.size(), position.orElse(-1), playMode);
        } catch (Exception e) {
            log.warn("Failed to restore play queue", e);
        } finally {
            log.info("restorePlayQueue: {} items took {}ms", count, System.currentTimeMillis() - start);
        }
    }

    private static String serializeContextType(ObjectIdentifier ctx) {
        return switch (ctx) {
            case ObjectIdentifier.ArtistIdentifier _ -> "artist";
            case ObjectIdentifier.AlbumIdentifier _ -> "album";
            case ObjectIdentifier.PlaylistIdentifier _ -> "playlist";
            case ObjectIdentifier.SongIdentifier _ -> "song";
        };
    }

    private static Optional<ObjectIdentifier> deserializeContext(String type, String id) {
        if (type == null || id == null) {
            return Optional.empty();
        }
        return Optional.of(switch (type) {
            case "artist" -> new ObjectIdentifier.ArtistIdentifier(id);
            case "album" -> new ObjectIdentifier.AlbumIdentifier(id);
            case "playlist" -> new ObjectIdentifier.PlaylistIdentifier(id);
            case "song" -> new ObjectIdentifier.SongIdentifier(id);
            default -> throw new IllegalArgumentException("Unknown context type: " + type);
        });
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

    private record ScrobbleSession(
            String songId,
            UUID requestId,
            long durationMs,
            boolean scrobbled
    ) {
        ScrobbleSession markScrobbled() {
            return new ScrobbleSession(songId, requestId, durationMs, true);
        }
    }

    private record Observation(
            @org.jspecify.annotations.Nullable ScrobbleSession session,
            long playbackStartedAtMs,
            PlaybinPlayer.PlayerStates state
    ) {}

    /**
     * Score a (session, startedAt) pair against the play-count threshold and record a scrobble
     * if met. Called from the player state listener with the PREVIOUS observation's values on
     * real transitions, and from {@link #shutdown()} with current values to catch clean quits
     * mid-song past the threshold.
     */
    private void evaluateScrobble(@org.jspecify.annotations.Nullable ScrobbleSession session, long startedAtMs) {
        if (session == null || session.scrobbled()) {
            return;
        }
        long durMs = session.durationMs();
        if (durMs <= 0 || startedAtMs <= 0) {
            return;
        }
        long posMs = System.currentTimeMillis() - startedAtMs;
        if (posMs <= 0) {
            return;
        }

        boolean thresholdMet;
        if (durMs < 30_000) {
            // Short tracks: must play nearly the full track:
            thresholdMet = posMs >= (durMs * 0.95);
        } else {
            // Normal tracks: 50% of track or 4 minutes, whichever comes first
            thresholdMet = posMs >= durMs / 2 || posMs >= 4 * 60 * 1000;
        }
        if (!thresholdMet) {
            return;
        }
        if (!scrobbleSession.compareAndSet(session, session.markScrobbled())) {
            return;
        }

        double durationSecond = durMs / 1000.0;
        double playedForMillis = Math.min(posMs, durMs);
        double playedForSecond = playedForMillis / 1000.0;
        double playedPercentage = playedForSecond / durationSecond;
        String playedPercentageStr = "%.1f".formatted(100 * playedPercentage);
        var songId = session.songId();
        doAsync(() -> {
            try {
                dbService.insertScrobble(songId, Instant.ofEpochMilli(startedAtMs));
                scrobbleService.triggerSubmit();
                log.info("Scrobble recorded for song: duration={}s playback={}s playedPercentage={} songId={}",
                        durationSecond, playedForSecond, playedPercentageStr, songId);
            } catch (Exception e) {
                log.error("Failed to record scrobble for song: {}", songId, e);
            }
        });
    }

    private void notifyListeners() {
        var state = this.currentState.getValue();
        Thread.startVirtualThread(() -> {
            for (StateListener stateListener : listeners) {
                stateListener.onStateChanged(state);
            }
        });
    }

    public void setWindow(ApplicationWindow window) {
        this.window = Optional.ofNullable(window);
    }
}
