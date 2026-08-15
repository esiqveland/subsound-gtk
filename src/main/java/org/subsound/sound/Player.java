package org.subsound.sound;

import java.time.Duration;
import java.util.Optional;

public interface Player {
    PlayerState getState();
    void seekTo(Duration duration);
    void onStateChanged(GstPlaybinPlayer.OnStateChanged listener);
    void removeOnStateChanged(GstPlaybinPlayer.OnStateChanged listener);

    /**
     * Registers a listener fired exactly once per ended stream, with the cause
     * (played to completion vs fatal stream error).
     * This is an edge event: unlike observing {@code state == END_OF_STREAM} via
     * {@link #onStateChanged}, it cannot re-fire on unrelated state notifications
     * (volume changes etc.) while the player still lingers in that state.
     */
    void onStreamEnded(GstPlaybinPlayer.OnStreamEnded listener);
    void removeOnStreamEnded(GstPlaybinPlayer.OnStreamEnded listener);

    /**
     * Live playback position. Unlike {@link PlayerState#source()}'s position,
     * which is only refreshed on discrete events (seek/pause/EOS), implementations extrapolate
     * this while playing.
     */
    default Optional<Duration> getCurrentPosition() {
        return getState().source().flatMap(Source::position);
    }
}
