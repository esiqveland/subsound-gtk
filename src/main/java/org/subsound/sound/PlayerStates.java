package org.subsound.sound;

import org.mpris.MediaPlayer2.MediaPlayer2Player;

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

    public MediaPlayer2Player.PlaybackStatus toMpris() {
        return switch (this) {
            case PAUSED -> MediaPlayer2Player.PlaybackStatus.Paused;
            case PLAYING, BUFFERING -> MediaPlayer2Player.PlaybackStatus.Playing;
            case READY, INIT, END_OF_STREAM -> MediaPlayer2Player.PlaybackStatus.Stopped;
        };
    }
}
