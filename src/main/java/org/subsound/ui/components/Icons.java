package org.subsound.ui.components;

import org.gnome.gdk.Display;
import org.gnome.gtk.IconTheme;
import org.subsound.configuration.constants.Constants;

// https://specifications.freedesktop.org/icon-naming-spec/latest/
// https://gitlab.gnome.org/GNOME/adwaita-icon-theme/-/tree/master/Adwaita/symbolic?ref_type=heads
public enum Icons {
    GoHome("go-home"),
    GoHomeSymbolic("go-home-symbolic"),
    UserHome("user-home"),
    UserHomeSymbolic("user-home-symbolic"),
    ContentLoading("content-loading"),
    ContentLoadingSymbolic("content-loading-symbolic"), // looks like three horizontal dots
    Starred("starred"),
    StarredSymbolic("starred-symbolic"),
    AddStar("star-new"),
    AddStarSymbolic("star-new-symbolic"),
    NetworkServer("network-server"),
    FolderRemote("folder-remote"),
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
    SearchEdit("edit-find-symbolic"),
    Artist("system-users-symbolic"),
    Albums("drive-multidisk-symbolic"),
    Playlists("view-list-symbolic"),
    ARTIST_ALBUM("avatar-default-symbolic"),
    Music("folder-music-symbolic"),
    Recent("document-open-recent-symbolic"),
    AlbumPlaceholder("media-optical-cd-audio-symbolic"),
    SettingsOld("settings-symbolic"),
    Settings("emblem-system-symbolic"),
    OpenMenu("view-more-symbolic"),
    ListAdd("list-add-symbolic"),
    ListRemove("list-remove-symbolic"),
    FolderDownload("folder-download-symbolic"),
    Microphone("audio-input-microphone-symbolic"),
    NetworkError("network-error-symbolic"),
    CheckmarkCircle("checkmark-circle-symbolic"),
    ;

    private final String iconName;
    // Resolved (app-id-prefixed) name once we've confirmed the bundled icon is
    // available in the icon theme; null until then. See getIconName().
    private String resolvedName;

    Icons(String iconName) {
        this.iconName = iconName;
    }

    /**
     * Returns the icon name to hand to GTK.
     *
     * <p>We bundle copies of the GNOME/Adwaita icons the app uses under an app-id
     * prefix (e.g. {@code io.github.subsoundorg.Subsound.go-home-symbolic}) so a host
     * icon theme cannot override them and break the layout. The copies are produced by
     * {@code install-bundled-icons.sh} (locally into {@code build/bundled-icons}, and
     * into {@code /app/share/icons/hicolor} for the flatpak build).
     *
     * <p>If the prefixed icon is present in the current theme we use it; otherwise we
     * fall back to the plain upstream name so nothing breaks when the bundle is absent
     * (e.g. headless tests, or an icon we couldn't source). The prefixed name still
     * ends in {@code -symbolic} for symbolic icons, so GTK keeps recoloring them.
     */
    public String getIconName() {
        if (resolvedName != null) {
            return resolvedName;
        }
        Display display = Display.getDefault();
        if (display == null) {
            // GTK not up yet (or headless): don't cache, just use the plain name.
            return iconName;
        }
        String prefixed = Constants.APP_ID + "." + iconName;
        if (IconTheme.getForDisplay(display).hasIcon(prefixed)) {
            resolvedName = prefixed;
            return prefixed;
        }
        // Not bundled in this environment; fall back (and re-check next time, in case
        // the search path is registered slightly later).
        return iconName;
    }
}
