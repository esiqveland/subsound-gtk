package org.subsound.sound;

import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.freedesktop.gstreamer.gst.Bus;
import org.freedesktop.gstreamer.gst.ClockTime;
import org.freedesktop.gstreamer.gst.Device;
import org.freedesktop.gstreamer.gst.DeviceMonitor;
import org.freedesktop.gstreamer.gst.Element;
import org.freedesktop.gstreamer.gst.ElementFactory;
import org.freedesktop.gstreamer.gst.Format;
import org.freedesktop.gstreamer.gst.Gst;
import org.freedesktop.gstreamer.gst.Message;
import org.freedesktop.gstreamer.gst.MessageType;
import org.freedesktop.gstreamer.gst.SeekFlags;
import org.freedesktop.gstreamer.gst.State;
import org.freedesktop.gstreamer.gst.Structure;
import org.gnome.glib.GError;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.glib.MainLoop;
import org.javagi.base.Out;
import org.mpris.MediaPlayer2.MediaPlayer2Player.PlaybackStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.utils.OsUtil;

import java.lang.foreign.MemoryLayout;
import java.lang.invoke.VarHandle;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.subsound.sound.PlaybinPlayer.PlayerStates.BUFFERING;
import static org.subsound.sound.PlaybinPlayer.PlayerStates.END_OF_STREAM;
import static org.subsound.sound.PlaybinPlayer.PlayerStates.INIT;
import static org.subsound.sound.PlaybinPlayer.PlayerStates.PAUSED;
import static org.subsound.sound.PlaybinPlayer.PlayerStates.PLAYING;
import static org.subsound.sound.PlaybinPlayer.PlayerStates.READY;
import static org.subsound.utils.OsUtil.OS.MACOS;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class PlaybinPlayer implements Player {
    private static final Logger log = LoggerFactory.getLogger(PlaybinPlayer.class);

    private static final int GST_PLAY_FLAG_AUDIO = 2;
    private static final int GST_PLAY_FLAG_SOFT_VOLUME = 0x00000010;
    private final List<OnStateChanged> listeners = new CopyOnWriteArrayList<>();
    private final List<OnStreamEnded> streamEndedListeners = new CopyOnWriteArrayList<>();

    public interface OnStateChanged {
        void onState(PlayerState next);
    }

    /** Why a stream ended: played to completion, or died on a fatal error. */
    public enum StreamEndCause {
        END_OF_STREAM,
        ERROR,
    }

    public interface OnStreamEnded {
        void onStreamEnded(StreamEndCause cause);
    }

    public void onStateChanged(OnStateChanged listener) {
        listeners.add(listener);
    }

    public void removeOnStateChanged(OnStateChanged listener) {
        listeners.remove(listener);
    }

    @Override
    public void onStreamEnded(OnStreamEnded listener) {
        streamEndedListeners.add(listener);
    }

    @Override
    public void removeOnStreamEnded(OnStreamEnded listener) {
        streamEndedListeners.remove(listener);
    }

    /** Fires the one-shot end-of-stream edge event (EOS or fatal stream error). */
    private void notifyStreamEnded(StreamEndCause cause) {
        for (OnStreamEnded listener : streamEndedListeners) {
            listener.onStreamEnded(cause);
        }
    }

    public PlayerState getState() {
        var source = Optional.ofNullable(currentUri).map(uri -> new Source(
                uri,
                Optional.ofNullable(this.position),
                Optional.ofNullable(this.duration)
        ));
        long startedAtMillis = this.playbackStartedAtMillis;
        long anchorAtMillis = this.positionAnchorAtMillis;
        return new PlayerState(
                this.playerStates,
                this.currentVolume,
                this.muteState.get(),
                startedAtMillis > 0 ? Optional.of(Instant.ofEpochMilli(startedAtMillis)) : Optional.empty(),
                anchorAtMillis > 0 ? Optional.of(Instant.ofEpochMilli(anchorAtMillis)) : Optional.empty(),
                source
        );
    }

    // a public read-only view of the player state
    @RecordBuilderFull
    public record PlayerState(
            PlayerStates state,
            double volume,
            boolean muted,
            Optional<Instant> playbackStartedAt,
            // Wall-clock instant corresponding to stream position = 0 for the current segment.
            // While PLAYING, current position ≈ now - positionAnchorAt. Moves on seek so UI can
            // extrapolate locally without waiting for position-notifications.
            Optional<Instant> positionAnchorAt,
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
    // macOS-only: the osxaudiosink we install as playbin's audio-sink, and the DeviceMonitor
    // whose bus we watch (on the default context / main thread) for Audio/Sink hotplug and
    // default-device changes. Both null on non-macOS.
    private Element osxAudioSink;
    private DeviceMonitor deviceMonitor;
    // Serializes (and debounces) re-anchoring off the GTK main thread; setState() blocks.
    private final ExecutorService deviceSwitchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-device-switch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean deviceSwitchPending = new AtomicBoolean(false);
    // Delay after an output device event before re-opening the sink: coalesces the remove+add
    // burst, lets macOS settle the new default, and paces the switch to feel natural.
    private static final Duration DEVICE_SWITCH_SETTLE = Duration.ofSeconds(1);
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerStates playerStates = INIT;
    private URI currentUri;
    private double currentVolume = 1.0;
    private Duration duration;
    private volatile Duration position;
    private volatile long playbackStartedAtMillis;
    // wall-clock epoch (ms) at which the current stream was (or would have been) at position=0.
    // While PLAYING: position ≈ currentTimeMillis() - positionAnchorAtMillis.
    // 0 means "no anchor yet" (set on first PLAYING transition / first position read).
    private volatile long positionAnchorAtMillis;
    private AtomicBoolean muteState = new AtomicBoolean(false);
    // pipeline state tracks the current state of the GstPipeline
    State pipelineState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);
    public void setMute(boolean muted) {
        boolean isMuted = muteState.get();
        log.debug("Playbin: set muted={} isMuted={}", muted, isMuted);
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
        this.playbackStartedAtMillis = 0;
        this.positionAnchorAtMillis = 0;
        this.position = null;
        var fileUri = uri.toString();
        // File.toURI() produces the single-slash "file:/path" form; GStreamer wants
        // "file:///path". Only rewrite that form — a blind replace would corrupt an
        // already-correct "file:///path" into "file://///path".
        if ("file".equals(uri.getScheme()) && fileUri.startsWith("file:/") && !fileUri.startsWith("file://")) {
            fileUri = "file://" + fileUri.substring("file:".length());
        }
        // https://gstreamer.freedesktop.org/documentation/additional/design/playback-gapless.html?gi-language=c
        // https://gstreamer.freedesktop.org/documentation/playback/playbin3.html?gi-language=c
        // the user wants to play a different track, playbin3 should be set back to READY or NULL state,
        // then the uri property should be set to the new location and then playbin3 be set to PLAYING state again.
        log.debug("Player: Change source to src={}", fileUri);
        // Save volume before state change (GStreamer may reset it)
        double savedVolume = this.currentVolume;
        boolean savedMute = this.muteState.get();
        var ready = this.playbinEl.setState(State.READY);
        log.debug("Player: Change source to src={} READY={}", fileUri, ready.name());
        this.playbinEl.set("uri", fileUri, null);
        // Restore volume after state change
        this.playbinEl.set("volume", savedVolume, null);
        this.playbinEl.set("mute", savedMute, null);
        if (startPlaying) {
            var playing = this.playbinEl.setState(State.PLAYING);
            log.debug("Player: Change source to src=" + fileUri + ": PLAYING=" + playing.name());
        } else {
            // Transition to PAUSED to preroll the media (required for seeking to work)
            var paused = this.playbinEl.setState(State.PAUSED);
            log.debug("Player: Change source to src=" + fileUri + ": PAUSED=" + paused.name());
        }
        this.notifyState();
    }

    private boolean busCall(Bus bus, Message msg) {
        Set<MessageType> msgTypes = msg.readType();
        var msgType = msgTypes.iterator().next();
        if (msgTypes.contains(MessageType.EOS)) {
            log.debug("Player: Got Event Type: {}", msgType.name());
            //GLib.print("End of stream\n");
            this.pause();
            this.setPlayerState(END_OF_STREAM);
            this.notifyState();
            this.notifyStreamEnded(StreamEndCause.END_OF_STREAM);
        } else if (msgTypes.contains(MessageType.ERROR)) {
            Out<GError> error = new Out<>();
            Out<String> debug = new Out<>();
            msg.parseError(error, debug);
            log.error("Player: GStreamer error: {}", error.get().readMessage());
            setPlayerState(END_OF_STREAM);
            notifyState();
            // Treat a fatal stream error like an ended stream so the queue skips ahead.
            notifyStreamEnded(StreamEndCause.ERROR);
        } else if (msgTypes.contains(MessageType.ASYNC_DONE)) {
            // if the seek operation succeeded.
            // Flushing seeks will trigger a preroll, which will emit MessageType.ASYNC_DONE
            this.onPositionChanged();
        } else if (msgTypes.contains(MessageType.STREAM_START)) {
            this.onDurationChanged();
            this.onPositionChanged();
        } else if (msgTypes.contains(MessageType.STATE_CHANGED)) {
            log.debug("Player: Got Event Type: {}", msgType.name());
            var src = msg.readSrc();
            if (!src.equals(this.playbinEl)) {
                return true;
            }
            log.debug("Player: playbin: Got Event Type: {}", msgType.name());
            this.onPipelineStateChanged();
            // TODO: read the new states by parsing msg ?
            //this.onPipelineStateChanged(getStateChanged(msg));
        } else if (msgTypes.contains(MessageType.BUFFERING)) {
            log.debug("Player: playbin: Got Event Type: {}", msgType.name());
            Out<Integer> percentOut = new Out<>();
            msg.parseBuffering(percentOut);
            int percent = percentOut.get();
            log.debug("Player: Got Event Type: {}: percent={}", msgType.name(), percent);
            this.setPlayerState(BUFFERING);
            this.onPipelineStateChanged();
            //this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.DURATION_CHANGED)) {
            log.debug("Player: Got Event Type: {}", msgType.name());
            // The duration of a pipeline changed. The application can get the new duration with a duration query
            this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.TOC)) {
            log.debug("Player: Got Event Type: {}", msgType.name());
        } else if (msgTypes.contains(MessageType.TAG)) {
            log.debug("Player: Got Event Type: {}", msgType.name());
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
        // Resync the position anchor to the authoritative pipeline position. Corrects any drift
        // between wall-clock extrapolation (UI) and actual stream progress.
        this.positionAnchorAtMillis = System.currentTimeMillis() - pos.toMillis();
        if (prev.toMillis() != position.toMillis()) {
            log.debug("Player.setPosition: {}", position.getSeconds());
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
            log.debug("Player.setDuration: {}", duration.getSeconds());
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
        if (currentUri == null) {
            // Nothing loaded (e.g. the last load failed and the source was unloaded).
            // playbin may still have a stale uri property set, and a bare
            // setState(PLAYING) would restart that old track.
            log.info("Player: play() ignored, no source loaded");
            return;
        }
        if (pipelineState == State.PLAYING) {
            return;
        }
        if (playerStates == END_OF_STREAM) {
            this.seekToStart();
        }
        if (isPaused()) {
            var pos = this.position;
            if (pos != null) {
                long now = System.currentTimeMillis();
                // play/pause transition: allow resetting the playbackStartedAtMillis
                // a user can start a song, go AFK for 10 minutes, resume, and the threshold is already "met" without them actually listening
                this.playbackStartedAtMillis = now - pos.toMillis();
                // Reanchor for UI extrapolation: wall-clock time grew during pause but stream
                // position didn't, so the anchor needs to jump forward by the pause duration.
                this.positionAnchorAtMillis = now - pos.toMillis();
            }
        }
        playbinEl.setState(State.PLAYING);
    }

    public void pause() {
        if (pipelineState == State.PAUSED) {
            return;
        }
        playbinEl.setState(State.PAUSED);
    }

    /**
     * Drop the current source entirely: tear the pipeline down to NULL and forget the
     * loaded uri, so a later {@link #play()} cannot resume a stale track. Used when
     * loading a new song fails (e.g. offline and not cached) after the previous track
     * was already paused.
     */
    public void unloadSource() {
        log.info("Player: unloadSource");
        this.currentUri = null;
        this.position = null;
        this.duration = null;
        this.playbackStartedAtMillis = 0;
        this.positionAnchorAtMillis = 0;
        var ret = this.playbinEl.setState(State.NULL);
        log.debug("Player: unloadSource: NULL={}", ret.name());
        // The bus does not deliver state-changed messages once the pipeline is NULL,
        // so update the mirrored state directly instead of waiting for busCall.
        this.pipelineState = State.NULL;
        this.setPlayerState(INIT);
        this.notifyState();
    }

    private void seekToStart() {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        playbinEl.seekSimple(Format.TIME, SeekFlags.FLUSH, 0);
        this.notifyState();
    }

    /**
     * Blocks until the GStreamer pipeline has finished its pending state transition.
     * Call this after setSource with startPlaying=false before seeking.
     */
    public void waitUntilReady() {
        var stateOut = new Out<State>();
        var pendingOut = new Out<State>();
        var result = playbinEl.getState(stateOut, pendingOut, Gst.CLOCK_TIME_NONE);
        log.info("waitUntilReady: result={} state={} pending={}", result.name(), stateOut.get(), pendingOut.get());
    }

    public void seekTo(Duration position) {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        long now = System.currentTimeMillis();
        // Scrobble semantics: reset session-start so a seek-to-end can't falsely cross threshold.
        this.playbackStartedAtMillis = now;
        // UI anchor: seeking DOES move stream position, so anchor shifts accordingly.
        this.positionAnchorAtMillis = now - position.toMillis();
        this.position = position;
        playbinEl.seekSimple(Format.TIME, Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH), position.toNanos());
        this.notifyState();
    }

    public void seekRelative(Duration offset) {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        var p = this.getCurrentPosition();
        if (p.isEmpty()) {
            return;
        }
        var nextPos = p.get().plus(offset);
        if (nextPos.getSeconds() < 0) {
            nextPos = Duration.ZERO;
        }
        long now = System.currentTimeMillis();
        this.playbackStartedAtMillis = now;
        this.positionAnchorAtMillis = now - nextPos.toMillis();
        this.position = nextPos;
        playbinEl.seekSimple(Format.TIME, Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH), nextPos.toNanos());
        this.notifyState();
    }

    private void onPipelineStateChanged() {
        var player = playbinEl;
        if (player == null) {
            return;
        }
        Out<State> stateOut = new Out<>();
        Out<State> stateOutPending = new Out<>();
        // Non-blocking (timeout 0). This runs on the GLib main-loop thread (via busCall); a
        // blocking CLOCK_TIME_NONE here deadlocks whenever the pipeline is mid-async transition
        // (e.g. osxaudiosink reopening on a device switch), because the ASYNC_DONE that would
        // complete the transition is delivered by this very loop. We just read the current state.
        player.getState(stateOut, stateOutPending, new ClockTime(0L));
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
            log.debug("Player: state changed: {} --> {}", oldState.name(), nextState.name());
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
        var nextPlayerState = switch (p.pipelineState) {
            case NULL, VOID_PENDING -> INIT;
            case READY -> READY;
            case PAUSED -> PAUSED;
            case PLAYING -> PLAYING;
        };
        var prevPlayerState = this.playerStates;
        this.playerStates = nextPlayerState;
        if (nextPlayerState == PLAYING && this.playbackStartedAtMillis == 0) {
            long now = System.currentTimeMillis();
            this.playbackStartedAtMillis = now;
            var pos = this.position;
            long posMs = pos != null ? pos.toMillis() : 0L;
            this.positionAnchorAtMillis = now - posMs;
        }
        // Snap `this.position` forward to GStreamer's authoritative clock at the transition out
        // of PLAYING. Without this, listeners would see a stale position (UI was extrapolating
        // locally past `this.position`) and the scrubber would jump backward on pause.
        if (prevPlayerState == PLAYING && nextPlayerState != PLAYING) {
            this.onPositionChanged();
        }
        this.notifyState();
    }

    public PlaybinPlayer() {
        this(null);
    }

    public PlaybinPlayer(URI initialFile) {
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

        // manually set "osxaudiosink" as the audio-sink for macos:
        // https://gstreamer.freedesktop.org/documentation/osxaudio/osxaudiosink.html?gi-language=c#osxaudiosink
        // this sink should have better handling when macos switches audio output than the default sink for playbin
        if (OsUtil.getOSPlatform() == MACOS) {
            try {
                //Element audioSink = ElementFactory.make("autoaudiosink", "audio-output");
                Element osxaudiosink = ElementFactory.make("osxaudiosink", "audio-output");
                if (osxaudiosink != null) {
                    osxaudiosink.set("device", 0, null); // 0 = default device
                    playbinEl.set("audio-sink", osxaudiosink, null);
                    this.osxAudioSink = osxaudiosink;
                    log.info("set audio-sink: osxaudiosink");
                } else {
                    log.info("unable to set audio-sink: osxaudiosink on macos");
                }
                // Detect audio-output hotplug / default-device changes, the same way
                // `gst-device-monitor-1.0 -f Audio/Sink` does: watch the monitor's bus with
                // gst_bus_add_watch on the DEFAULT GMainContext (this constructor runs on the
                // main thread), and start() the monitor. This is the identical mechanism used
                // for the playbin bus above (line ~588) — the GTK application main loop iterates
                // the default context on the main thread and dispatches these messages. A custom
                // context on a background thread does NOT work, because GTK never iterates it.
                //
                // Gated on GStreamer >= 1.28: only then did osxaudiodeviceprovider gain start/stop
                // monitoring (dynamic add/remove/change events). On older versions the monitor
                // never posts events, so wiring it up is pointless.
                if (isGstAtLeast(1, 28)) {
                    var deviceMonitor = new DeviceMonitor();
                    this.deviceMonitor = deviceMonitor;
                    deviceMonitor.addFilter("Audio/Sink", null);
                    var monitorBus = deviceMonitor.getBus();
                    monitorBus.addWatch(0, (b, msg) -> {
                        try {
                            handleDeviceMessage(msg);
                        } catch (Throwable t) {
                            log.error("deviceMonitor: error handling bus message", t);
                        }
                        return true; // keep the watch installed
                    });
                    if (!deviceMonitor.start()) {
                        log.warn("Gst.DeviceMonitor: failed to start");
                    }
                } else {
                    log.info(
                            "Gst.DeviceMonitor: audio-output monitoring requires GStreamer >= 1.28, have '{}'; skipping",
                            Gst.versionString()
                    );
                }
            } catch (Throwable t) {
                log.error("error in setting up macos support: ", t);
                throw t;
            }
        }

        playbinEl.onNotify("volume", params -> this.onVolumeChanged());
        playbinEl.onNotify("mute", params -> this.onMuteChanged());
        // make sure we update the values on construction:
        this.onVolumeChanged();
        this.onMuteChanged();

        playerLoopThread = new Thread(() -> {
            try {
                loop.run();
            } catch (Throwable t) {
                log.info("playerLoopThread: crashed", t);
            } finally {
                log.info("playerLoopThread: run finished");
            }
            // Out of the main loop, clean up nicely
//            GLib.print("Returned, stopping playback\n");
//            pipeline.setState(State.NULL);
//
//            GLib.print("Deleting pipeline\n");
//            Source.remove(busWatchId);
        }, "player-main-loop");
        playerLoopThread.start();

        // We set the input filename to the source element
        if (initialFile != null) {
            //var fileUri = initialFile.toString();
            //GLib.print("Now playing: %s\n", fileUri);
            this.setSource(initialFile, false);
        }
        //GLib.print("Running...\n");
    }

    /**
     * Handle a message from the macOS audio-output {@link DeviceMonitor} bus. Runs on the GTK
     * main thread (the default-context watch installed in the constructor), so it must stay
     * non-blocking — any sink re-anchor via setState() must be dispatched to a separate thread.
     * For now it only logs; note that is-default in an add/change event is racy (macOS flips the
     * system default just after the device appears), so the follow-default logic must re-read the
     * current default rather than trust the flag on the event.
     */
    // VarHandle onto GstMessage.type (a plain int). See messageTypeRaw().
    private static final VarHandle MESSAGE_TYPE_FIELD =
            Message.getMemoryLayout().varHandle(MemoryLayout.PathElement.groupElement("type"));

    /**
     * Read GstMessage.type as a raw int. We must NOT use {@link Message#readType()} here:
     * GStreamer's DEVICE_* (and other "extended") message types set bit 31
     * ({@code GST_MESSAGE_EXTENDED = 1<<31}), and java-gi 0.15's {@code Interop.intToEnumSet}
     * infinite-loops decoding that bitfield — it pins the calling thread (here the GTK main
     * thread) at 100% CPU and freezes the UI. Reading the field directly and comparing to the
     * known enum int values via {@link MessageType#getValue()} sidesteps the broken decode.
     */
    private static int messageTypeRaw(Message msg) {
        return (int) MESSAGE_TYPE_FIELD.get(msg.handle(), 0L);
    }

    /** True if the linked/loaded GStreamer runtime is at least major.minor. */
    private static boolean isGstAtLeast(int major, int minor) {
        Out<Integer> maj = new Out<>();
        Out<Integer> min = new Out<>();
        Out<Integer> micro = new Out<>();
        Out<Integer> nano = new Out<>();
        Gst.version(maj, min, micro, nano);
        return maj.get() > major || (maj.get() == major && min.get() >= minor);
    }

    /**
     * unique-id of a device. On GstOsxAudioDevice this exists both as a GObject property and in
     * the properties Structure; we read the Structure so it works for any provider.
     */
    private static String uniqueId(Device d) {
        Structure props = d.getProperties();
        return props != null ? props.getString("unique-id") : null;
    }

    /**
     * Whether the device is the current system default. This is NOT a GObject property on
     * GstOsxAudioDevice (getProperty("is-default") throws); it lives only in the device's
     * properties Structure. Returns null if absent. Note the flag is racy at add/change time —
     * macOS flips the default just after a device appears — so follow-default logic must re-read.
     */
    private static Boolean isDefault(Device d) {
        Structure props = d.getProperties();
        if (props == null || !props.hasField("is-default")) {
            return null;
        }
        Out<Boolean> out = new Out<>();
        return props.getBoolean("is-default", out) ? out.get() : null;
    }

    private void handleDeviceMessage(Message msg) {
        int type = messageTypeRaw(msg);
        if (type == MessageType.DEVICE_ADDED.getValue()) {
            Out<Device> devOut = new Out<>();
            msg.parseDeviceAdded(devOut);
            var d = devOut.get();
            log.info("🎵 Audio output connected: {} unique-id={} is-default={}", d.getDisplayName(), uniqueId(d), isDefault(d));
            onDeviceEvent();
        } else if (type == MessageType.DEVICE_REMOVED.getValue()) {
            Out<Device> devOut = new Out<>();
            msg.parseDeviceRemoved(devOut);
            var d = devOut.get();
            log.info("🎵 Audio output disconnected: {} unique-id={}", d.getDisplayName(), uniqueId(d));
            onDeviceEvent();
        } else if (type == MessageType.DEVICE_CHANGED.getValue()) {
            Out<Device> devOut = new Out<>();
            Out<Device> changedOut = new Out<>();
            msg.parseDeviceChanged(devOut, changedOut);
            var d = devOut.get();
            log.info("🎵 Audio output changed: {} unique-id={} is-default={}",
                    d.getDisplayName(), uniqueId(d), isDefault(d));
            onDeviceEvent();
        } else {
            // e.g. GST_MESSAGE_DEVICE_MONITOR_STARTED (new in 1.28) — confirms async start done.
            log.debug("deviceMonitor: message type={}", type);
        }
    }

    /** unique-id of the current system default Audio/Sink, or null if none/undeterminable. */
    private String resolveDefaultOutputId() {
        var devices = deviceMonitor.getDevices();
        if (devices == null) {
            return null;
        }
        for (Device d : devices) {
            if (Boolean.TRUE.equals(isDefault(d))) {
                return uniqueId(d);
            }
        }
        return null;
    }

    /**
     * React to a device add/remove/change on the GTK main thread by scheduling a (debounced)
     * re-resolve of the current default output off-thread. We do NOT trust is-default on the event
     * itself — it is racy (macOS flips the default a moment after the device appears) — so we sleep
     * briefly to let CoreAudio settle, then read the authoritative default and re-anchor if needed.
     */
    private void onDeviceEvent() {
        if (this.osxAudioSink == null) {
            return;
        }
        if (!deviceSwitchPending.compareAndSet(false, true)) {
            return; // a re-resolve is already scheduled; it will observe the latest state
        }
        deviceSwitchExecutor.submit(() -> {
            try {
                // Coalesce event bursts and let macOS settle the new default. Also paces the
                // switch so it doesn't feel jarringly faster than native output switching.
                Thread.sleep(DEVICE_SWITCH_SETTLE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                deviceSwitchPending.set(false);
                return;
            }
            // Clear before doing the work so events arriving during the switch schedule a fresh run.
            deviceSwitchPending.set(false);
            try {
                cycleAudioOutput();
            } catch (Throwable t) {
                log.error("audio-device-switch: failed", t);
            }
        });
    }

    /**
     * Re-open the pipeline so osxaudiosink (device=0) reconnects to the current system default
     * output, preserving playback position. osxaudiosink only re-resolves the default when it
     * reopens its device, which requires passing through READY. Bouncing just the sink element
     * leaves it without a running clock/segment and playback stalls, so we bounce the whole
     * playbin (PLAYING→READY→PAUSED to preroll on the new default, seek back, then PLAYING). This
     * is the same re-init a track skip does, minus the track change.
     *
     * Runs on the audio-device-switch thread (never the main/loop thread), so blocking on
     * getState() for preroll here is safe — the main loop stays free to deliver ASYNC_DONE.
     *
     * For now we cycle unconditionally on any output device change rather than trying to tell
     * whether the default actually moved (is-default is racy at event time). device=0 makes this
     * safe: worst case it reconnects to the same default with a brief gap.
     */
    private void cycleAudioOutput() {
        if (this.osxAudioSink == null) {
            return;
        }
        // Nothing loaded (e.g. the startup device-enumeration burst before any track): nothing to
        // follow, and reopening during pipeline bring-up is pointless.
        if (this.currentUri == null) {
            log.debug("audio-device-switch: no source loaded, skipping output switch");
            return;
        }
        boolean wasPlaying = this.pipelineState == State.PLAYING;
        Duration pos = getCurrentPosition().orElse(Duration.ZERO);

        // Bounce the pipeline through READY so the sink closes and reopens on the new default.
        this.playbinEl.setState(State.READY);
        this.playbinEl.setState(State.PAUSED);
        // Block until PAUSED preroll completes so the seek below lands accurately. Safe here: this
        // is the switch thread, not the main loop.
        Out<State> stateOut = new Out<>();
        Out<State> pendingOut = new Out<>();
        this.playbinEl.getState(stateOut, pendingOut, Gst.CLOCK_TIME_NONE);
        this.playbinEl.seekSimple(Format.TIME, Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH), pos.toNanos());
        if (wasPlaying) {
            this.playbinEl.setState(State.PLAYING);
        }
        log.info("🎵 Switched audio output; resumed at {}s (wasPlaying={}) default-now={}",
                pos.getSeconds(), wasPlaying, resolveDefaultOutputId());
    }

    public Optional<Duration> getCurrentPosition() {
        // While PLAYING, `this.position` is only refreshed on discrete events (seek, pause, EOS)
        // since the positionPublisher was removed. Extrapolate from the wall-clock anchor so
        // on-demand consumers (e.g. MPRIS Position queries) get a live value.
        if (this.playerStates == PLAYING) {
            long anchor = this.positionAnchorAtMillis;
            if (anchor > 0) {
                long posMs = Math.max(0L, System.currentTimeMillis() - anchor);
                var dur = this.duration;
                if (dur != null && posMs > dur.toMillis()) {
                    posMs = dur.toMillis();
                }
                return Optional.of(Duration.ofMillis(posMs));
            }
        }
        return Optional.ofNullable(this.position);
    }

    public double getVolume() {
        return this.currentVolume;
    }

    // volume is a linear scale from [0.0, 1.0]
    public void setVolume(double cubicVolume) {
        double vol = Math.max(0.0, Math.min(1.0, cubicVolume));
        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
        double linearVolume = cubicToLinearVolume(vol);
        log.debug("Playbin: set volume to %.2f cubic=%.2f".formatted(linearVolume, cubicVolume));
        // https://gstreamer.freedesktop.org/documentation/audio/gststreamvolume.html?gi-language=c#GstStreamVolume
        this.playbinEl.set("volume", linearVolume, null);
    }

    private void onVolumeChanged() {
        // https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin:volume
        // when casting to boxed Double, it sometimes comes out as 0.0 while changing the volume??
        double volume = (double) playbinEl.getProperty("volume");
        log.debug("Playbin: onVolumeChanged: %.2f".formatted(volume));
        var linearVolume = volume;
        this.currentVolume = linearVolume;
        this.notifyState();
    }

    private void onMuteChanged() {
        boolean isMuted = (Boolean) playbinEl.getProperty("mute");
        log.debug("Playbin: onMuteChanged: muted={}", isMuted);
        this.muteState.set(isMuted);
        this.notifyState();
    }

    public void quit() {
        if (!quitState.compareAndSet(false, true)) {
            // quit has already been called
            return;
        }
        this.playbinEl.setState(State.NULL);
        // Stop the macOS audio-device monitor (if any). Its bus watch lives on the default
        // context serviced by the GTK application main loop, so removing the source here (via
        // stop()) is enough; there is no dedicated thread/loop of ours to tear down.
        if (deviceMonitor != null) {
            deviceMonitor.stop();
        }
        deviceSwitchExecutor.shutdownNow();
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

