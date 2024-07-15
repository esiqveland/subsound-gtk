package io.github.jwharm.javagi.examples.playsound.sound;

import io.github.jwharm.javagi.base.Out;
import org.freedesktop.gstreamer.gst.*;
import org.gnome.glib.*;

import java.lang.Thread;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

// TODO: Try to make it work closer to a audio-only playbin:
//  https://gstreamer.freedesktop.org/documentation/playback/playbin.html?gi-language=c#playbin
//
// GstSink:
// gconfaudiosink vs autoaudiosink
public class SoundPlayer {

    public static final String FILENAME = "src/main/resources/example.ogg";

    Thread mainLoopThread;
    MainContext playerContext;
    MainLoop loop;
    Pipeline pipeline;
    Element source, demuxer, decoder, conv, sink;
    Bus bus;
    int busWatchId;
    State currentState = State.NULL;

    private final AtomicBoolean quitState = new AtomicBoolean(false);

    private boolean busCall(Bus bus, Message msg) {
        Set<MessageType> msgType = msg.readType();
        if (msgType.contains(MessageType.EOS)) {
            GLib.print("End of stream\n");
            loop.quit();
        } else if (msgType.contains(MessageType.ERROR)) {
            Out<GError> error = new Out<>();
            Out<String> debug = new Out<>();
            msg.parseError(error, debug);

            GLib.printerr("Error: %s\n", error.get().readMessage());

            loop.quit();
        } else if (msgType.contains(MessageType.STATE_CHANGED)) {
            this.onStateChanged();
        }

        return true;
    }

    public boolean isPlaying() {
        return currentState == State.PLAYING;
    }
    public boolean isPaused() {
        return currentState == State.PAUSED;
    }

    public void play() {
        if (currentState == State.PLAYING) {
            return;
        }
        var player = pipeline;
        player.setState(State.PLAYING);
    }

    public void pause() {
        if (currentState == State.PAUSED) {
            return;
        }
        var player = pipeline;
        player.setState(State.PAUSED);
    }

    private void onStateChanged() {
        var player = pipeline;
        if (player == null) {
            return;
        }
        Out<State> stateOut = new Out<>();
        Out<State> stateOutPending = new Out<>();
        player.getState(stateOut, stateOutPending, Gst.CLOCK_TIME_NONE);
        var oldState = this.currentState;
        var nextState = stateOut.get();
        if (oldState != nextState) {
            this.currentState = nextState;
            System.out.printf("Player: state changed: %s --> %s\n", oldState.name(), nextState.name());
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

        // We set the input filename to the source element
        source.set("location", FILENAME, null);

        // We add a message handler
        bus = pipeline.getBus();
        busWatchId = bus.addWatch(0, this::busCall);

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

