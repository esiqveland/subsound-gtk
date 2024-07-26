package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.CacheSong;
import io.github.jwharm.javagi.examples.playsound.persistence.SongCache.GetSongResult;
import io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class AppManager {
    private static final Logger log = LoggerFactory.getLogger(AppManager.class);
    private final PlaybinPlayer player;
    private final SongCache songCache;
    private final AtomicReference<AppState> currentState = new AtomicReference<>();
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();

    public AppManager(PlaybinPlayer player, SongCache songCache) {
        this.player = player;
        this.songCache = songCache;
        player.onStateChanged(next -> this.setState(old -> new AppState(
                old.nowPlaying, next
        )));
        this.currentState.set(buildState());
    }

    private AppState buildState() {
        return new AppState(Optional.empty(), this.player.getState());
    }

    public AppState getState() {
        return this.currentState.get();
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

    public record NowPlaying(
            SongInfo song,
            GetSongResult cacheResult
    ) {
    }

    public record AppState(
            Optional<NowPlaying> nowPlaying,
            PlaybinPlayer.PlayerState player
    ) {
    }

    public void setSource(SongInfo songInfo) {
        GetSongResult song = songCache.getSong(new CacheSong(
                "123",
                songInfo.id(),
                songInfo.streamUri(),
                songInfo.suffix(),
                songInfo.streamSuffix(),
                songInfo.size()
        ));
        log.info("cached: result={} id={} title={}", song.result().name(), songInfo.id(), songInfo.title());
        this.setState(old -> new AppState(
                Optional.of(new NowPlaying(songInfo, song)),
                old.player
        ));
        this.player.setSource(song.uri());
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
