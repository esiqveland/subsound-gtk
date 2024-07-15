package io.github.jwharm.javagi.examples.playsound.sound;

import io.github.jwharm.javagi.base.Out;
import org.freedesktop.gstreamer.gst.*;
import org.gnome.glib.GError;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.glib.MainLoop;
import org.gnome.gobject.Value;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class PlaybinPlayer {

    private static final int GST_PLAY_FLAG_AUDIO = 2;

    public enum PlayerState {
        // The initial state of the player
        INIT,
        BUFFERING,
        READY,
        PAUSED,
        PLAYING,
        END_OF_STREAM,
    }

    Thread mainLoopThread;
    MainContext playerContext;
    MainLoop loop;
    Element playbin;
    Bus bus;
    int busWatchId;
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerState playerState = PlayerState.INIT;
    private double currentVolume = 1.0;
    private Duration duration;
    // pipeline state tracks the current state of the GstPipeline
    State pipelineState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);

    // volume is a linear scale from [0.0, 1.0]
    public void setVolume(double linearVolume) {
        double vol = Math.max(0.0, linearVolume);
        vol = Math.min(1.0, vol);

        // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
        double cubicVolume = toVolumeCubic(vol);
        System.out.printf("Playbin: set volume to %.2f cubic=%.2f\n", vol, cubicVolume);
        // https://gstreamer.freedesktop.org/documentation/audio/gststreamvolume.html?gi-language=c#GstStreamVolume
        this.playbin.set("volume", cubicVolume, null);
    }

    public void setSource(URI uri) {
        var fileUri = uri.toString();
        System.out.println("Player: Change source to src=" + fileUri);
        this.playbin.setState(State.NULL);
        this.playbin.set("uri", fileUri, null);
    }

    private boolean busCall(Bus bus, Message msg) {
        Set<MessageType> msgTypes = msg.readType();
        var msgType = msgTypes.iterator().next();
        if (msgTypes.contains(MessageType.EOS)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            GLib.print("End of stream\n");
            this.pause();
            this.setPlayerState(PlayerState.END_OF_STREAM);
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
            if (src.equals(this.playbin)) {
                System.out.println("Player: playbin: Got Event Type: " + msgType.name());
            } else {
                return true;
            }
            this.onPipelineStateChanged();
            //this.onPipelineStateChanged(getStateChanged(msg));
        } else if (msgTypes.contains(MessageType.BUFFERING)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            Out<Integer> percentOut = new Out<>();
            msg.parseBuffering(percentOut);
            int percent = percentOut.get();
            System.out.println("Player: Got Event Type: " + msgType.name() + ": percent=" + percent);
            this.onPipelineStateChanged();
            this.onDurationChanged();
            this.setPlayerState(PlayerState.BUFFERING);
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

    private StateChanged getStateChanged(Message msg) {
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
        var success = playbin.queryDuration(Format.TIME, dur);
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
        }
    }

    private void setPlayerState(PlayerState playerState) {
        this.playerState = playerState;
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
        if (playerState == PlayerState.END_OF_STREAM) {
            this.seekToStart();
        }
        var player = playbin;
        player.setState(State.PLAYING);
    }

    private void seekToStart() {
        //playbin.seek(1.0, Format.TIME, SeekFlags.FLUSH, SeekType.SET, 0, SeekType.NONE, 0);
        playbin.seekSimple(Format.TIME, SeekFlags.FLUSH, 0);
    }

    public void pause() {
        if (pipelineState == State.PAUSED) {
            return;
        }
        var player = playbin;
        player.setState(State.PAUSED);
    }

    private void onPipelineStateChanged() {
        var player = playbin;
        if (player == null) {
            return;
        }
        Out<State> stateOut = new Out<>();
        Out<State> stateOutPending = new Out<>();
        player.getState(stateOut, stateOutPending, Gst.CLOCK_TIME_NONE);
        onPipelineStateChanged(new StateChanged(this.pipelineState, stateOut.get()));
    }

    private void onPipelineStateChanged(StateChanged stateChanged) {
        var player = playbin;
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
                PlayerState playerState
        ) {
        }
        var p = new PlayState(this.pipelineState, this.playerState);

        switch (p.pipelineState) {
            case VOID_PENDING -> {
            }
            case NULL -> this.playerState = PlayerState.INIT;
            case READY -> this.playerState = PlayerState.READY;
            case PAUSED -> this.playerState = PlayerState.PAUSED;
            case PLAYING -> this.playerState = PlayerState.PLAYING;
        }
    }

    public PlaybinPlayer(URI initialFile) {
        // Initialisation
        Gst.init(new Out<>(new String[]{}));
        //Gst.initCheck(new Out<>(args));

        playerContext = new MainContext();
        loop = new MainLoop(playerContext, false);

        // Create gstreamer elements
        playbin = ElementFactory.make("playbin", "playbin");

        if (Stream.of(playbin).anyMatch(Objects::isNull)) {
            GLib.printerr("playbin element could not be created. Exiting.\n");
            return;
        }
        // Set up the pipeline

        // We add a message handler
        bus = playbin.getBus();
        busWatchId = bus.addWatch(0, this::busCall);
        playbin.onNotify("volume", (params) -> {
            this.onVolumeChanged();
        });

        var fileUri = initialFile.toString();

        // We set the input filename to the source element
        playbin.set("uri", fileUri, null);
        playbin.set("flags", GST_PLAY_FLAG_AUDIO, null);

        // Set the pipeline
        GLib.print("Now playing: %s\n", fileUri);

        // Iterate
        GLib.print("Running...\n");

        mainLoopThread = new Thread(() -> {
            loop.run();
            System.out.println("mainLoopThread: run finished??");
            // Out of the main loop, clean up nicely
//            GLib.print("Returned, stopping playback\n");
//            pipeline.setState(State.NULL);
//
//            GLib.print("Deleting pipeline\n");
//            Source.remove(busWatchId);
        }, "player-main-loop");
        mainLoopThread.start();
    }

    private void onVolumeChanged() {
        double volume = (Double) playbin.getProperty("volume");
        System.out.printf("Playbin: onVolumeChanged: %.2f\n", volume);
        this.currentVolume = volume;
    }

    public void quit() {
        if (!quitState.compareAndSet(false, true)) {
            // quit has already been called
            return;
        }
        this.playbin.setState(State.NULL);
        if (loop.isRunning()) {
            loop.quit();
        }
        try {
            mainLoopThread.join(10_000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // https://github.com/GStreamer/gst-plugins-base/blob/master/gst-libs/gst/audio/streamvolume.c#L169
    public static double toVolumeCubic(double linearVolume) {
        return Math.pow(linearVolume, 1.0 / 3.0);
    }

}

