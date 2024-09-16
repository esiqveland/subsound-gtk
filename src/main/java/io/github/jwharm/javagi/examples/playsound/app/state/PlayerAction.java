package io.github.jwharm.javagi.examples.playsound.app.state;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.SongInfo;
import io.github.jwharm.javagi.examples.playsound.ui.components.SettingsPage.SettingsInfo;

import java.time.Duration;
import java.util.List;

public sealed interface PlayerAction {
    record Play() implements PlayerAction {}
    record Pause() implements PlayerAction {}
    record PlayPrev() implements PlayerAction {}
    record PlayNext() implements PlayerAction {}
    record SeekTo(Duration position) implements PlayerAction {}
    record PlayQueue(List<SongInfo> queue, int position) implements PlayerAction {
        @Override
        public String toString() {
            return "PlayQueue([%d songs], position=%d)".formatted(this.queue.size(), position);
        }
    }
    record PlayPositionInQueue(int position) implements PlayerAction {}
    record PlaySong(SongInfo song) implements PlayerAction {}
    record Enqueue(SongInfo song) implements PlayerAction {}
    record Star(SongInfo song) implements PlayerAction {}
    record Unstar(SongInfo song) implements PlayerAction {}

    // not strictly player actions:
    record SaveConfig(SettingsInfo next) implements PlayerAction {}
}
