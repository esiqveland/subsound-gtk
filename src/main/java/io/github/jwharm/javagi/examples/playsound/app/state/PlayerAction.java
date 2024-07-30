package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;

import java.time.Duration;
import java.util.List;

public sealed interface PlayerAction {
    record Play() implements PlayerAction {}
    record Pause() implements PlayerAction {}
    record PlayPrev() implements PlayerAction {}
    record PlayNext() implements PlayerAction {}
    record SeekTo(Duration position) implements PlayerAction {}
    record PlayQueue(List<SongInfo> queue, int position) implements PlayerAction {}
    record PlayPositionInQueue(int position) implements PlayerAction {}
    record Enqueue(SongInfo song) implements PlayerAction {}
}
