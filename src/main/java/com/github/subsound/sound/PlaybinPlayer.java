package com.github.subsound.sound;

import io.github.jwharm.javagi.base.Out;
import com.github.subsound.utils.OsUtil;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.freedesktop.gstreamer.gst.*;
import org.gnome.glib.GError;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.glib.MainLoop;
import org.mpris.MediaPlayer2.MediaPlayer2Player;
import org.mpris.MediaPlayer2.MediaPlayer2Player.PlaybackStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.github.subsound.sound.PlaybinPlayer.PlayerStates.*;
import static com.github.subsound.utils.OsUtil.OS.MACOS;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class PlaybinPlayer {
    private static final Logger log = LoggerFactory.getLogger(PlaybinPlayer.class);

    private static final int GST_PLAY_FLAG_AUDIO = 2;
    private static final int GST_PLAY_FLAG_SOFT_VOLUME = 0x00000010;
    private static final List<OnStateChanged> listeners = new CopyOnWriteArrayList<>();

    public interface OnStateChanged {
        void onState(PlayerState next);
    }

    public void onStateChanged(OnStateChanged listener) {
        listeners.add(listener);
    }

    public void removeOnStateChanged(OnStateChanged listener) {
        listeners.remove(listener);
    }

    public PlayerState getState() {
        var source = Optional.ofNullable(currentUri).map(uri -> new Source(
                uri,
                Optional.ofNullable(this.position),
                Optional.ofNullable(this.duration)
        ));
        return new PlayerState(
                this.playerStates,
                this.currentVolume,
                this.muteState.get(),
                source
        );
    }

    // a public read-only view of the player state
    @RecordBuilderFull
    public record PlayerState(
            PlayerStates state,
            double volume,
            boolean muted,
            Optional<Source> source
    ) implements PlaybinPlayerPlayerStateBuilder.With {
    }

    @RecordBuilderFull
    public record Source(
            URI current,
            Optional<Duration> position,
            Optional<Duration> duration
    ) implements PlaybinPlayerSourceBuilder.With {
    }

    public enum PlayerStates {
        // The initial state of the player
        INIT,
        BUFFERING,
        READY,
        PAUSED,
        PLAYING,
        END_OF_STREAM,
        ;

        public boolean isPlaying() {
            return this == PLAYING;
        }

        public PlaybackStatus toMpris() {
            return switch (this) {
                case PAUSED -> PlaybackStatus.Paused;
                case PLAYING, BUFFERING -> PlaybackStatus.Playing;
                case READY, INIT, END_OF_STREAM -> PlaybackStatus.Stopped;
            };
        }
    }

    private final Thread playerLoopThread;
    private final MainContext playerContext;
    private final MainLoop loop;
    Element playbinEl;
    Bus bus;
    int busWatchId;
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerStates playerStates = INIT;
    // positionPublisher updates the player position while state is PLAYING
    private final Thread positionPublisher;
    private URI currentUri;
    private double currentVolume = 1.0;
    private Duration duration;
    private Duration position;
    private AtomicBoolean muteState = new AtomicBoolean(false);
    // pipeline state tracks the current state of the GstPipeline
    State pipelineState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);

    public void setMute(boolean muted) {
        boolean isMuted = muteState.get();
        System.out.printf("Playbin: set muted=%b isMuted=%b\n", muted, isMuted);
        if (isMuted == muted) {
            return;
        }
        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst/playback/gstplaybin2.c#L900
        this.playbinEl.setProperty("mute", muted);
    }

    public boolean getMute() {
        return muteState.get();
    }

    public record AudioSource(
            URI uri,
            Duration estimatedDuration
    ){}
    public void setSource(AudioSource src, boolean startPlaying) {
        this.duration = src.estimatedDuration;
        this.setSource(src.uri, startPlaying);
    }

    public void setSource(URI uri, boolean startPlaying) {
        this.currentUri = uri;
        var fileUri = uri.toString();
        if ("file".equals(uri.getScheme())) {
            fileUri = fileUri.replace("file:/", "file:///");
        }
        // https://gstreamer.freedesktop.org/documentation/additional/design/playback-gapless.html?gi-language=c
        // https://gstreamer.freedesktop.org/documentation/playback/playbin3.html?gi-language=c
        // the user wants to play a different track, playbin3 should be set back to READY or NULL state,
        // then the uri property should be set to the new location and then playbin3 be set to PLAYING state again.
        System.out.println("Player: Change source to src=" + fileUri);
        var ready = this.playbinEl.setState(State.READY);
        System.out.println("Player: Change source to src=" + fileUri + ": READY=" + ready.name());
        this.playbinEl.set("uri", fileUri, null);
        if (startPlaying) {
            var playing = this.playbinEl.setState(State.PLAYING);
            System.out.println("Player: Change source to src=" + fileUri + ": PLAYING=" + playing.name());
        }
        this.notifyState();
    }

    private boolean busCall(Bus bus, Message msg) {
        Set<MessageType> msgTypes = msg.readType();
        var msgType = msgTypes.iterator().next();
        if (msgTypes.contains(MessageType.EOS)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            GLib.print("End of stream\n");
            this.pause();
            this.setPlayerState(END_OF_STREAM);
            this.notifyState();
        } else if (msgTypes.contains(MessageType.ERROR)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            Out<GError> error = new Out<>();
            Out<String> debug = new Out<>();
            msg.parseError(error, debug);

            GLib.printerr("Error: %s\n", error.get().readMessage());

            loop.quit();
        } else if (msgTypes.contains(MessageType.ASYNC_DONE)) {
            // if the seek operation succeeded.
            // Flushing seeks will trigger a preroll, which will emit MessageType.ASYNC_DONE
            this.onPositionChanged();
        } else if (msgTypes.contains(MessageType.STREAM_START)) {
            this.onDurationChanged();
            this.onPositionChanged();
        } else if (msgTypes.contains(MessageType.STATE_CHANGED)) {
            //System.out.println("Player: Got Event Type: " + msgType.name());
            var src = msg.readSrc();
            if (!src.equals(this.playbinEl)) {
                return true;
            }
            System.out.println("Player: playbin: Got Event Type: " + msgType.name());
            this.onPipelineStateChanged();
            // TODO: read the new states by parsing msg ?
            //this.onPipelineStateChanged(getStateChanged(msg));
        } else if (msgTypes.contains(MessageType.BUFFERING)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            Out<Integer> percentOut = new Out<>();
            msg.parseBuffering(percentOut);
            int percent = percentOut.get();
            System.out.println("Player: Got Event Type: " + msgType.name() + ": percent=" + percent);
            this.setPlayerState(BUFFERING);
            this.onPipelineStateChanged();
            //this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.DURATION_CHANGED)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            // The duration of a pipeline changed. The application can get the new duration with a duration query
            this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.TOC)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
        } else if (msgTypes.contains(MessageType.TAG)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
        }

        return true;
    }

    private record StateChanged(
            State oldState,
            State newState
    ) {
    }

    private static StateChanged getStateChanged(Message msg) {
        var oldState = new Out<State>();
        var newState = new Out<State>();
        var pendingState = new Out<State>();
        msg.parseStateChanged(oldState, newState, pendingState);
        return new StateChanged(
                oldState.get(),
                newState.get()
        );
    }

    private void onPositionChanged() {
        var dur = new Out<Long>();
        var success = playbinEl.queryPosition(Format.TIME, dur);
        if (success) {
            Long nanos = dur.get();
            if (nanos == null) {
                return;
            }
            // normalize to millis:
            var pos = Duration.ofMillis(Duration.ofNanos(nanos).toMillis());
            this.setPosition(pos);
        }
    }

    private void setPosition(Duration pos) {
        var prev = this.position;
        if (prev == null) {
            prev = Duration.ZERO;
        }
        this.position = pos;
        if (prev.toMillis() != position.toMillis()) {
            //System.out.printf("Player.setPosition: %d\n", position.getSeconds());
            this.notifyState();
        }
    }

    private void onDurationChanged() {
        var dur = new Out<Long>();
        var success = playbinEl.queryDuration(Format.TIME, dur);
        if (success) {
            Long nanos = dur.get();
            if (nanos == null) {
                return;
            }
            this.setDuration(Duration.ofNanos(nanos));
        }
    }

    private void setDuration(Duration duration) {
        var prev = this.duration;
        if (prev == null) {
            prev = Duration.ZERO;
        }
        this.duration = duration;
        if (prev.toMillis() != duration.toMillis()) {
            System.out.printf("Player.setDuration: %d\n", duration.getSeconds());
            this.notifyState();
        }
    }


    private void notifyState() {
        var nextState = getState();
        for (OnStateChanged listener : listeners) {
            listener.onState(nextState);
        }
    }

    private void setPlayerState(PlayerStates playerStates) {
        this.playerStates = playerStates;
    }

    public boolean isPlaying() {
        return pipelineState == State.PLAYING;
    }

    public boolean isPaused() {
        return pipelineState == State.PAUSED;
    }

    public void play() {
        if (pipelineState == State.PLAYING) {
            return;
        }
        if (playerStates == END_OF_STREAM) {
            this.seekToStart();
        }
        playbinEl.setState(State.PLAYING);
    }

    public void pause() {
        if (pipelineState == State.PAUSED) {
            return;
        }
        playbinEl.setState(State.PAUSED);
    }

    private void seekToStart() {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        playbinEl.seekSimple(Format.TIME, SeekFlags.FLUSH, 0);
        this.notifyState();
    }

    public void seekTo(Duration position) {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        playbinEl.seekSimple(Format.TIME, Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH), position.toNanos());
        this.notifyState();
    }

    private void onPipelineStateChanged() {
        var player = playbinEl;
        if (player == null) {
            return;
        }
        Out<State> stateOut = new Out<>();
        Out<State> stateOutPending = new Out<>();
        player.getState(stateOut, stateOutPending, Gst.CLOCK_TIME_NONE);
        onPipelineStateChanged(new StateChanged(this.pipelineState, stateOut.get()));
    }

    private void onPipelineStateChanged(StateChanged stateChanged) {
        var player = playbinEl;
        if (player == null) {
            return;
        }
        var oldState = stateChanged.oldState;
        var nextState = stateChanged.newState;
        if (oldState != nextState) {
            this.onChangedPipelineState(nextState);
            System.out.printf("Player: state changed: %s --> %s\n", oldState.name(), nextState.name());
        }
    }

    private void onChangedPipelineState(State nextState) {
        this.pipelineState = nextState;

        record PlayState(
                State pipelineState,
                PlayerStates playerState
        ) {
        }
        var p = new PlayState(this.pipelineState, this.playerStates);
        this.playerStates = switch (p.pipelineState) {
            case NULL, VOID_PENDING -> INIT;
            case READY -> READY;
            case PAUSED -> PAUSED;
            case PLAYING -> PLAYING;
        };
        this.notifyState();
    }

    public PlaybinPlayer() {
        this(null);
    }

    public PlaybinPlayer(URI initialFile) {
        // Initialisation
        // Init should be done from Main function
        //Gst.init(new Out<>(new String[]{}));
        //Gst.initCheck(new Out<>(args));

        playerContext = new MainContext();
        loop = new MainLoop(playerContext, false);

        // Create gstreamer elements
        playbinEl = ElementFactory.make("playbin", "Subsound");
        if (Stream.of(playbinEl).anyMatch(Objects::isNull)) {
            GLib.printerr("playbin element could not be created. Exiting.\n");
            throw new RuntimeException("playbin element could not be created. Exiting.");
        }
        // playbin: we only want to enable audio:
        // https://gstreamer.freedesktop.org/documentation/playback/playsink.html?gi-language=c#GstPlayFlags
        // MacOS: needs soft-volume flag
        int flags = GST_PLAY_FLAG_AUDIO;
        if (OsUtil.getOSPlatform() == MACOS) {
            flags = flags | GST_PLAY_FLAG_SOFT_VOLUME;
        }
        playbinEl.set("flags", flags, null);

        // We add a message handler
        bus = playbinEl.getBus();
        busWatchId = bus.addWatch(0, this::busCall);

        playbinEl.onNotify("volume", params -> this.onVolumeChanged());
        playbinEl.onNotify("mute", params -> this.onMuteChanged());
        // make sure we update the values on construction:
        this.onVolumeChanged();
        this.onMuteChanged();

        playerLoopThread = new Thread(() -> {
            loop.run();
            System.out.println("playerLoopThread: run finished??");
            // Out of the main loop, clean up nicely
//            GLib.print("Returned, stopping playback\n");
//            pipeline.setState(State.NULL);
//
//            GLib.print("Deleting pipeline\n");
//            Source.remove(busWatchId);
        }, "player-main-loop");
        playerLoopThread.start();

        this.positionPublisher = Thread.startVirtualThread(() -> {
            try {
                while (true) {
                    if (!playerLoopThread.isAlive()) {
                        log.info("positionPublisher: playerLoopThread died, exiting");
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    if (playerStates != PLAYING) {
                        continue;
                    }
                    // update the position
                    this.onPositionChanged();
                }
            } finally {
                log.info("positionPublisher: exited");
            }
        });

        // We set the input filename to the source element
        if (initialFile != null) {
            var fileUri = initialFile.toString();
            GLib.print("Now playing: %s\n", fileUri);
            this.setSource(initialFile, false);
        }
        GLib.print("Running...\n");
    }

    public double getVolume() {
        return this.currentVolume;
    }

    // volume is a linear scale from [0.0, 1.0]
    public void setVolume(double cubicVolume) {
        double vol = Math.max(0.0, Math.min(1.0, cubicVolume));
        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
        double linearVolume = cubicToLinearVolume(vol);
        System.out.printf("Playbin: set volume to %.2f cubic=%.2f\n", linearVolume, cubicVolume);
        // https://gstreamer.freedesktop.org/documentation/audio/gststreamvolume.html?gi-language=c#GstStreamVolume
        this.playbinEl.set("volume", linearVolume, null);
    }

    private void onVolumeChanged() {
        // https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin:volume
        // when casting to boxed Double, it sometimes comes out as 0.0 while changing the volume??
        double volume = (double) playbinEl.getProperty("volume");
        System.out.printf("Playbin: onVolumeChanged: %.2f\n", volume);
        var linearVolume = volume;
        this.currentVolume = linearVolume;
        this.notifyState();
    }

    private void onMuteChanged() {
        boolean isMuted = (Boolean) playbinEl.getProperty("mute");
        System.out.printf("Playbin: onMuteChanged: muted=%b\n", isMuted);
        this.muteState.set(isMuted);
        this.notifyState();
    }

    public void quit() {
        if (!quitState.compareAndSet(false, true)) {
            // quit has already been called
            return;
        }
        this.playbinEl.setState(State.NULL);
        if (loop.isRunning()) {
            loop.quit();
        }
        try {
            playerLoopThread.join(10_000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
    public static double toVolumeCubic(double linearVolume) {
        return Math.pow(linearVolume, 1.0 / 3.0);
    }

    // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
    public static double cubicToLinearVolume(double cubicVolume) {
        return cubicVolume * cubicVolume * cubicVolume;
    }

}

