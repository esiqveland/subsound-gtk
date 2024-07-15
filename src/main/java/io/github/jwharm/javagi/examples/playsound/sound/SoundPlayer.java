package io.github.jwharm.javagi.examples.playsound.sound;

import io.github.jwharm.javagi.base.Out;
import org.freedesktop.gstreamer.gst.*;
import org.gnome.glib.*;

import java.lang.Thread;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class SoundPlayer {

    public enum PlayerState {
        // The initial state of the player
        INIT,
        BUFFERING,
        READY,
        PAUSED,
        PLAYING,
        END_OF_STREAM,
    }

    public static final String FILENAME = "src/main/resources/example.ogg";

    Thread mainLoopThread;
    MainContext playerContext;
    MainLoop loop;
    Pipeline pipeline;
    Element source, demuxer, decoder, conv, sink;
    Bus bus;
    int busWatchId;
    // PlayerState should be the public view of the state of the player/player Pipeline
    PlayerState playerState = PlayerState.INIT;
    private Duration duration;
    // pipeline state tracks the current state of the GstPipeline
    State pipelineState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);

    private boolean busCall(Bus bus, Message msg) {
        Set<MessageType> msgTypes = msg.readType();
        var msgType = msgTypes.iterator().next();
        if (msgTypes.contains(MessageType.EOS)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            GLib.print("End of stream\n");
            this.pause();
            this.setPlayerState(PlayerState.END_OF_STREAM);
            //pipeline.seek();
            //loop.quit();
        } else if (msgTypes.contains(MessageType.ERROR)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
            Out<GError> error = new Out<>();
            Out<String> debug = new Out<>();
            msg.parseError(error, debug);

            GLib.printerr("Error: %s\n", error.get().readMessage());

            loop.quit();
        } else if (msgTypes.contains(MessageType.STATE_CHANGED)) {
            //System.out.println("Player: Got Event Type: " + msgType.name());
            this.onPipelineStateChanged();
        } else if (msgTypes.contains(MessageType.BUFFERING)) {
            System.out.println("Player: Got Event Type: " + msgType.name());
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

    private void onDurationChanged() {
        var dur = new Out<Long>();
        var success = pipeline.queryDuration(Format.TIME, dur);
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
        var player = pipeline;
        player.setState(State.PLAYING);
    }

    public void pause() {
        if (pipelineState == State.PAUSED) {
            return;
        }
        var player = pipeline;
        player.setState(State.PAUSED);
    }

    private void onPipelineStateChanged() {
        var player = pipeline;
        if (player == null) {
            return;
        }
        Out<State> stateOut = new Out<>();
        Out<State> stateOutPending = new Out<>();
        player.getState(stateOut, stateOutPending, Gst.CLOCK_TIME_NONE);
        var oldState = this.pipelineState;
        var nextState = stateOut.get();
        if (oldState != nextState) {
            this.setPipelineState(nextState);
            System.out.printf("Player: state changed: %s --> %s\n", oldState.name(), nextState.name());
        }
    }

    private void setPipelineState(State nextState) {
        this.pipelineState = nextState;

        switch (nextState) {
            case VOID_PENDING -> {
            }
            case NULL -> this.playerState = PlayerState.INIT;
            case READY -> this.playerState = PlayerState.READY;
            case PAUSED -> this.playerState = PlayerState.PAUSED;
            case PLAYING -> this.playerState = PlayerState.PLAYING;
        }
    }

    private void onPadAdded(Pad pad) {
        // We can now link this pad with the vorbis-decoder sink pad
        GLib.print("Dynamic pad created, linking demuxer/decoder\n");

        Pad sinkpad = decoder.getStaticPad("sink");

        if (sinkpad != null) {
            pad.link(sinkpad);
        } else {
            GLib.printerr("Sink pad not set!\n");
        }
    }

    public SoundPlayer(String[] args) {
        // Initialisation
        Gst.init(new Out<>(args));
        //Gst.initCheck(new Out<>(args));

        playerContext = new MainContext();
        loop = new MainLoop(playerContext, false);

        // Create gstreamer elements
        pipeline = new Pipeline("audio-player-example");
//        pipeline = new Pipeline("audio-player");
        source = ElementFactory.make("filesrc", "file-source");
        demuxer = ElementFactory.make("oggdemux", "ogg-demuxer");
        decoder = ElementFactory.make("vorbisdec", "vorbis-decoder");
        conv = ElementFactory.make("audioconvert", "converter");
        sink = ElementFactory.make("autoaudiosink", "audio-output");

        if (Stream.of(source, demuxer, decoder, conv, sink).anyMatch(Objects::isNull)) {
            GLib.printerr("One element could not be created. Exiting.\n");
            return;
        }
        // Set up the pipeline

        // We add a message handler
        bus = pipeline.getBus();
        busWatchId = bus.addWatch(0, this::busCall);

        // We set the input filename to the source element
        source.set("location", FILENAME, null);

        // We add all elements into the pipeline
        // file-source | ogg-demuxer | vorbis-decoder | converter | alsa-output
        pipeline.addMany(source, demuxer, decoder, conv, sink, null);

        // We link the elements together
        // file-source -> ogg-demuxer ~> vorbis-decoder -> converter -> alsa-output
        source.link(demuxer);
        decoder.linkMany(conv, sink, null);
        demuxer.onPadAdded(this::onPadAdded);

        // Set the pipeline
        GLib.print("Now playing: %s\n", FILENAME);
        //pipeline.setState(State.PLAYING);

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

    public void quit() {
        if (!quitState.compareAndSet(false, true)) {
            // quit has already been called
            return;
        }
        this.pipeline.setState(State.NULL);
        if (loop.isRunning()) {
            loop.quit();
        }
        try {
            mainLoopThread.join(10_000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

