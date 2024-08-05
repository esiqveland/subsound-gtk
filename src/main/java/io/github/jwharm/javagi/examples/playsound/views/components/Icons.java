package io.github.jwharm.javagi.examples.playsound.views.components;

// https://specifications.freedesktop.org/icon-naming-spec/latest/
public enum Icons {
    PLAY("media-playback-start-symbolic"),
    PAUSE("media-playback-pause-symbolic"),
    SkipBackward("media-skip-backward-symbolic"),
    SkipForward("media-skip-forward-symbolic"),
    PlaylistRepeat("media-playlist-repeat-symbolic"),
    PlaylistRepeatSong("media-playlist-repeat-song-symbolic"),
    PlaylistShuffle("media-playlist-shuffle-symbolic"),
    PlaylistConsecutive("media-playlist-consecutive-symbolic"),
    VolumeHigh("audio-volume-high-symbolic"),
    VolumeMedium("audio-volume-medium-symbolic"),
    VolumeLow("audio-volume-low-symbolic"),
    VolumeMuted("audio-volume-muted-symbolic"),
    VolumeControl("multimedia-volume-control-symbolic"),
    NetworkOffline("network-offline-symbolic"),
    RefreshView("view-refresh-symbolic"),
    Search("system-search-symbolic"),
    ;

    private final String iconName;
    Icons(String iconName) {
        this.iconName = iconName;
    }

    public String getIconName() {
        return iconName;
    }
}
