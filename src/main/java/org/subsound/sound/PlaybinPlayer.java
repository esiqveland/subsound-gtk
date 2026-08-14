package org.subsound.sound;

import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.freedesktop.gstreamer.gst.Bus;
import org.freedesktop.gstreamer.gst.ClockTime;
import org.freedesktop.gstreamer.gst.Element;
import org.freedesktop.gstreamer.gst.ElementFactory;
import org.freedesktop.gstreamer.gst.Format;
import org.freedesktop.gstreamer.gst.Gst;
import org.freedesktop.gstreamer.gst.Message;
import org.freedesktop.gstreamer.gst.MessageType;
import org.freedesktop.gstreamer.gst.SeekFlags;
import org.freedesktop.gstreamer.gst.State;
import org.gnome.glib.GError;
import org.gnome.glib.GLib;
import org.javagi.base.Out;
import org.mpris.MediaPlayer2.MediaPlayer2Player.PlaybackStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.utils.OsUtil;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
                toDuration(this.positionMillis),
                toDuration(this.durationMillis)
        ));
        long startedAtMillis = this.playbackStartedAtMillis;
        long anchorAtMillis = this.positionAnchorAtMillis;
        return new PlayerState(
                this.playerStates,
                this.currentVolume,
                this.muted,
                startedAtMillis > 0 ? Optional.of(Instant.ofEpochMilli(startedAtMillis)) : Optional.empty(),
                anchorAtMillis > 0 ? Optional.of(Instant.ofEpochMilli(anchorAtMillis)) : Optional.empty(),
                source
        );
    }

    private static Optional<Duration> toDuration(long millis) {
        return millis < 0 ? Optional.empty() : Optional.of(Duration.ofMillis(millis));
    }

    /** Forget all timing for the current stream: no position, no anchor, no scrobble session. */
    private void resetTiming() {
        this.positionMillis = NO_TIME;
        this.positionAnchorAtMillis = 0;
        this.playbackStartedAtMillis = 0;
    }

    /**
     * Re-anchor UI extrapolation so the stream reads as being at {@code posMillis} right now.
     *
     * <p>{@code restartScrobble} controls the scrobble session: a seek or a fresh start restarts it
     * at "now" (so seeking to the end can't falsely cross the threshold), while resuming from pause
     * back-dates it by the already-played portion (so an AFK pause doesn't count as listening).
     */
    private void anchorAt(long posMillis, boolean restartScrobble) {
        long now = System.currentTimeMillis();
        this.positionMillis = posMillis;
        this.positionAnchorAtMillis = now - posMillis;
        this.playbackStartedAtMillis = restartScrobble ? now : now - posMillis;
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

    Element playbinEl;
    // ReplayGain gain stage inserted via playbin's "audio-filter". Its "volume" property carries
    // the per-track normalization multiplier and multiplies with playbin's own user-volume. Null
    // if the "volume" element could not be created (playback then proceeds without normalization).
    private Element replayGainVolumeEl;
    // Last applied ReplayGain multiplier; re-applied after each READY bounce in setSource.
    private volatile double replayGainScale = 1.0;
    Bus bus;
    int busWatchId;
    // macOS-only: keeps osxaudiosink pointed at the system default output. Null on every other
    // platform, and on macOS when the sink or a new-enough GStreamer isn't available.
    private MacosOutputFollower outputFollower;
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerStates playerStates = INIT;
    private URI currentUri;
    private double currentVolume = 1.0;
    // Position and duration are held as primitive millis rather than Duration: they are written
    // from the bus watch (GTK main thread) and from setSource on virtual threads, and a primitive
    // cannot be observed half-updated or null the way a nullable Duration field could.
    private static final long NO_TIME = -1L;
    private volatile long durationMillis = NO_TIME;
    private volatile long positionMillis = NO_TIME;
    private volatile long playbackStartedAtMillis;
    // wall-clock epoch (ms) at which the current stream was (or would have been) at position=0.
    // While PLAYING: position ≈ currentTimeMillis() - positionAnchorAtMillis.
    // 0 means "no anchor yet" (set on first PLAYING transition / first position read).
    private volatile long positionAnchorAtMillis;
    // Mirrors playbin's "mute" property; updated from the property notify callback.
    private volatile boolean muted;
    // pipeline state tracks the current state of the GstPipeline
    State pipelineState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);
    public void setMute(boolean muted) {
        boolean isMuted = this.muted;
        log.debug("Playbin: set muted={} isMuted={}", muted, isMuted);
        if (isMuted == muted) {
            return;
        }
        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst/playback/gstplaybin2.c#L900
        this.playbinEl.setProperty("mute", muted);
    }

    public boolean getMute() {
        return this.muted;
    }

    public record AudioSource(
            URI uri,
            Duration estimatedDuration,
            // ReplayGain linear volume multiplier for this track (1.0 = no change).
            double replayGainScale
    ){
        public AudioSource(URI uri, Duration estimatedDuration) {
            this(uri, estimatedDuration, 1.0);
        }
    }
    public void setSource(AudioSource src, boolean startPlaying) {
        this.durationMillis = src.estimatedDuration.toMillis();
        this.replayGainScale = src.replayGainScale;
        this.setSource(src.uri, startPlaying);
    }

    /**
     * Update the ReplayGain multiplier for the currently-playing track without restarting it.
     * The "volume" element's property is live-controllable, so the change takes effect immediately.
     */
    public void setReplayGainScale(double scale) {
        this.replayGainScale = scale;
        if (this.replayGainVolumeEl != null) {
            this.replayGainVolumeEl.set("volume", scale, null);
        }
    }

    private void setSource(URI uri, boolean startPlaying) {
        this.currentUri = uri;
        this.resetTiming();
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
        boolean savedMute = this.muted;
        var ready = this.playbinEl.setState(State.READY);
        log.debug("Player: Change source to src={} READY={}", fileUri, ready.name());
        this.playbinEl.set("uri", fileUri, null);
        // Restore volume after state change
        this.playbinEl.set("volume", savedVolume, null);
        this.playbinEl.set("mute", savedMute, null);
        // Apply this track's ReplayGain multiplier (independent of the user volume above).
        if (this.replayGainVolumeEl != null) {
            this.replayGainVolumeEl.set("volume", this.replayGainScale, null);
        }
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

    private void onPositionChanged() {
        var nanos = queryTime(true);
        if (nanos == null) {
            return;
        }
        long posMillis = nanos / 1_000_000L;
        long prev = this.positionMillis;
        this.positionMillis = posMillis;
        // Resync the position anchor to the authoritative pipeline position. Corrects any drift
        // between wall-clock extrapolation (UI) and actual stream progress.
        this.positionAnchorAtMillis = System.currentTimeMillis() - posMillis;
        if (prev != posMillis) {
            log.debug("Player.setPosition: {}", posMillis / 1000);
            this.notifyState();
        }
    }

    private void onDurationChanged() {
        var nanos = queryTime(false);
        if (nanos == null) {
            return;
        }
        long durMillis = nanos / 1_000_000L;
        long prev = this.durationMillis;
        this.durationMillis = durMillis;
        if (prev != durMillis) {
            log.debug("Player.setDuration: {}", durMillis / 1000);
            this.notifyState();
        }
    }

    /** Queries the pipeline's position or duration in nanoseconds; null if unavailable. */
    private Long queryTime(boolean wantPosition) {
        var out = new Out<Long>();
        boolean success = wantPosition
                ? playbinEl.queryPosition(Format.TIME, out)
                : playbinEl.queryDuration(Format.TIME, out);
        return success ? out.get() : null;
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
            long pos = this.positionMillis;
            if (pos >= 0) {
                // Wall-clock time grew during the pause but the stream position didn't, so both the
                // UI anchor and the scrobble session shift forward by the length of the pause.
                anchorAt(pos, false);
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
        this.durationMillis = NO_TIME;
        this.resetTiming();
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
        this.doSeek(position.toMillis());
    }

    public void seekRelative(Duration offset) {
        var p = this.getCurrentPosition();
        if (p.isEmpty()) {
            return;
        }
        this.doSeek(Math.max(0L, p.get().plus(offset).toMillis()));
    }

    private void doSeek(long posMillis) {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        // Seeking moves the stream position, so the UI anchor shifts with it, and the scrobble
        // session restarts so a seek-to-end can't falsely cross the threshold.
        anchorAt(posMillis, true);
        playbinEl.seekSimple(
                Format.TIME,
                Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH),
                posMillis * 1_000_000L
        );
        this.notifyState();
    }

    private void onPipelineStateChanged() {
        Out<State> stateOut = new Out<>();
        Out<State> pendingOut = new Out<>();
        // Non-blocking (timeout 0). This runs on the GLib main-loop thread (via busCall); a
        // blocking CLOCK_TIME_NONE here deadlocks whenever the pipeline is mid-async transition
        // (e.g. osxaudiosink reopening on a device switch), because the ASYNC_DONE that would
        // complete the transition is delivered by this very loop. We just read the current state.
        playbinEl.getState(stateOut, pendingOut, new ClockTime(0L));
        var nextState = stateOut.get();
        var prevState = this.pipelineState;
        if (prevState == nextState) {
            return;
        }
        log.debug("Player: state changed: {} --> {}", prevState.name(), nextState.name());
        this.pipelineState = nextState;

        var prevPlayerState = this.playerStates;
        this.playerStates = switch (nextState) {
            case NULL, VOID_PENDING -> INIT;
            case READY -> READY;
            case PAUSED -> PAUSED;
            case PLAYING -> PLAYING;
        };
        if (this.playerStates == PLAYING && this.playbackStartedAtMillis == 0) {
            anchorAt(Math.max(this.positionMillis, 0L), true);
        }
        // Snap the position forward to GStreamer's authoritative clock at the transition out of
        // PLAYING. Without this, listeners would see a stale position (the UI was extrapolating
        // locally past it) and the scrubber would jump backward on pause.
        if (prevPlayerState == PLAYING && this.playerStates != PLAYING) {
            this.onPositionChanged();
        }
        this.notifyState();
    }

    public PlaybinPlayer() {
        this(null);
    }

    public PlaybinPlayer(URI initialFile) {
        // Create gstreamer elements
        playbinEl = ElementFactory.make("playbin", "Subsound");
        if (playbinEl == null) {
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

        // ReplayGain: insert a "volume" element as playbin's audio-filter. We compute the
        // normalization multiplier ourselves (from the server's ReplayGain dB values) and drive
        // this element's "volume" property; it multiplies with playbin's own user-facing volume,
        // so the two stay independent. "volume" is a gst-plugins-base core element (always present)
        // and its "volume" property is live-controllable. The audio-filter is only picked up while
        // playbin is in NULL/READY, which is where we are here at construction.
        replayGainVolumeEl = ElementFactory.make("volume", "replaygain-volume");
        if (replayGainVolumeEl != null) {
            playbinEl.set("audio-filter", replayGainVolumeEl, null);
        } else {
            log.warn("Could not create 'volume' element; ReplayGain normalization disabled");
        }

        // We add a message handler
        bus = playbinEl.getBus();
        busWatchId = bus.addWatch(0, this::busCall);

        // manually set "osxaudiosink" as the audio-sink for macos:
        // https://gstreamer.freedesktop.org/documentation/osxaudio/osxaudiosink.html?gi-language=c#osxaudiosink
        // this sink should have better handling when macos switches audio output than the default sink for playbin
        if (OsUtil.getOSPlatform() == MACOS) {
            try {
                this.outputFollower = MacosOutputFollower.install(playbinEl, new MacosOutputFollower.PlayerHooks() {
                    @Override
                    public boolean hasSource() {
                        return currentUri != null;
                    }

                    @Override
                    public boolean isPlaying() {
                        return pipelineState == State.PLAYING;
                    }

                    @Override
                    public Duration currentPosition() {
                        return getCurrentPosition().orElse(Duration.ZERO);
                    }

                    @Override
                    public void pause() {
                        PlaybinPlayer.this.pause();
                    }
                });
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

        // We set the input filename to the source element
        if (initialFile != null) {
            //var fileUri = initialFile.toString();
            //GLib.print("Now playing: %s\n", fileUri);
            this.setSource(initialFile, false);
        }
        //GLib.print("Running...\n");
    }

    public Optional<Duration> getCurrentPosition() {
        // While PLAYING, `positionMillis` is only refreshed on discrete events (seek, pause, EOS)
        // since the positionPublisher was removed. Extrapolate from the wall-clock anchor so
        // on-demand consumers (e.g. MPRIS Position queries) get a live value.
        if (this.playerStates == PLAYING) {
            long anchor = this.positionAnchorAtMillis;
            if (anchor > 0) {
                long posMs = Math.max(0L, System.currentTimeMillis() - anchor);
                long dur = this.durationMillis;
                if (dur >= 0 && posMs > dur) {
                    posMs = dur;
                }
                return Optional.of(Duration.ofMillis(posMs));
            }
        }
        return toDuration(this.positionMillis);
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
        this.muted = isMuted;
        this.notifyState();
    }

    public void quit() {
        if (!quitState.compareAndSet(false, true)) {
            // quit has already been called
            return;
        }
        this.playbinEl.setState(State.NULL);
        if (outputFollower != null) {
            outputFollower.stop();
        }
        // The bus watch is attached to the default main context; drop it so no further messages
        // are dispatched into a torn-down pipeline. Fully qualified: the simple name `Source`
        // is taken by this class's own record.
        org.gnome.glib.Source.remove(busWatchId);
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

