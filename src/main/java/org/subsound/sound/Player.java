package org.subsound.sound;

import java.time.Duration;

public interface Player {
    PlaybinPlayer.PlayerState getState();
    void seekTo(Duration duration);
    void onStateChanged(PlaybinPlayer.OnStateChanged listener);
    void removeOnStateChanged(PlaybinPlayer.OnStateChanged listener);
    /**
     * Live playback position. Unlike {@link PlaybinPlayer.PlayerState#source()}'s position,
     * which is only refreshed on discrete events (seek/pause/EOS), implementations extrapolate
     * this while playing.
     */
    default Optional<Duration> getCurrentPosition() {
        return getState().source().flatMap(PlaybinPlayer.Source::position);
    }
}
