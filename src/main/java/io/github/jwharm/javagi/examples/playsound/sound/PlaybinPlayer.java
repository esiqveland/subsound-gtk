package io.github.jwharm.javagi.examples.playsound.sound;

import io.github.jwharm.javagi.base.Out;
import org.freedesktop.gstreamer.gst.Bus;
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
import org.gnome.glib.MainContext;
import org.gnome.glib.MainLoop;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.BUFFERING;
import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.END_OF_STREAM;
import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.INIT;
import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.PAUSED;
import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.PLAYING;
import static io.github.jwharm.javagi.examples.playsound.sound.PlaybinPlayer.PlayerStates.READY;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class PlaybinPlayer {

    private static final int GST_PLAY_FLAG_AUDIO = 2;
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
    public record PlayerState(
            PlayerStates state,
            double volume,
            boolean muted,
            Optional<Source> source
    ) {
    }

    public record Source(
            URI current,
            Optional<Duration> duration
    ) {
    }

    public enum PlayerStates {
        // The initial state of the player
        INIT,
        BUFFERING,
        READY,
        PAUSED,
        PLAYING,
        END_OF_STREAM,
    }

    private final Thread playerLoopThread;
    private final MainContext playerContext;
    private final MainLoop loop;
    //    Pipeline playbinEl;
    Element playbinEl;
    Bus bus;
    int busWatchId;
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerStates playerStates = INIT;
    private URI currentUri;
    private double currentVolume = 1.0;
    private Duration duration;
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

    public void setSource(URI uri) {
        this.currentUri = uri;
        var fileUri = uri.toString();
        if ("file".equals(uri.getScheme())) {
            fileUri = fileUri.replace("file:/", "file:///");
        }
        System.out.println("Player: Change source to src=" + fileUri);
        this.playbinEl.setState(State.READY);
        this.playbinEl.set("uri", fileUri, null);
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
        } else if (msgTypes.contains(MessageType.STATE_CHANGED)) {
            //System.out.println("Player: Got Event Type: " + msgType.name());
            var src = msg.readSrc();
            if (src.equals(this.playbinEl)) {
                System.out.println("Player: playbin: Got Event Type: " + msgType.name());
            } else {
                return true;
            }
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
            this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.DURATION_CHANGED)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            // The duration of a pipeline changed. The application can get the new duration with a duration query
            this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.TOC)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            this.onDurationChanged();
        } else if (msgTypes.contains(MessageType.TAG)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            this.onDurationChanged();
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
        playbinEl = ElementFactory.make("playbin", "playbin");
        if (Stream.of(playbinEl).anyMatch(Objects::isNull)) {
            GLib.printerr("playbin element could not be created. Exiting.\n");
            throw new RuntimeException("playbin element could not be created. Exiting.");
        }
        // playbin: we only want to enable audio:
        playbinEl.set("flags", GST_PLAY_FLAG_AUDIO, null);
        // Set up the pipeline
//        playbinEl = new Pipeline("audio-player-example");
//        playbinEl.add(playbinEl);

        // We add a message handler
        bus = playbinEl.getBus();
        busWatchId = bus.addWatch(0, this::busCall);

        playbinEl.onNotify("volume", params -> this.onVolumeChanged());
        playbinEl.onNotify("mute", params -> this.onMuteChanged());
        // make sure we update the values on construction:
        this.onVolumeChanged();
        this.onMuteChanged();

        // We set the input filename to the source element
        if (initialFile != null) {
            var fileUri = initialFile.toString();
            GLib.print("Now playing: %s\n", fileUri);
            this.setSource(initialFile);
        }
        GLib.print("Running...\n");

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
    }

    public double getVolume() {
        return this.currentVolume;
    }

    // volume is a linear scale from [0.0, 1.0]
    public void setVolume(double linearVolume) {
        double vol = Math.max(0.0, Math.min(1.0, linearVolume));
        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
        double cubicVolume = toVolumeCubic(vol);
        System.out.printf("Playbin: set volume to %.2f cubic=%.2f\n", vol, cubicVolume);
        // https://gstreamer.freedesktop.org/documentation/audio/gststreamvolume.html?gi-language=c#GstStreamVolume
        this.playbinEl.set("volume", cubicVolume, null);
    }

    private void onVolumeChanged() {
        double volume = (Double) playbinEl.getProperty("volume");
        System.out.printf("Playbin: onVolumeChanged: %.2f\n", volume);
        var linearVolume = cubicToLinearVolume(volume);
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

