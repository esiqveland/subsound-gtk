package com.github.subsound.sound;

import java.time.Duration;

public interface Player {
    PlaybinPlayer.PlayerState getState();
    void seekTo(Duration duration);
    void onStateChanged(PlaybinPlayer.OnStateChanged listener);
    void removeOnStateChanged(PlaybinPlayer.OnStateChanged listener);
}
