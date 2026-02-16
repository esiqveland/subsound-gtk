package com.github.subsound.ui.models;

import org.gnome.glib.Type;
import org.javagi.gobject.types.Types;

public enum GDownloadState {
    NONE, // ie. not downloaded. need to online and connected to server to play
    PENDING, // song is in the download-list and waiting for download to start
    DOWNLOADING, // song is in the download-list and is in progress
    DOWNLOADED; // song is in the download-list and completed
    //CACHED, // TODO: CACHED: track status of songs we have in the cache, but not explicitly in the download list
    //ERROR, // song download attempt failed

    private static Type gtype = Types.register(GDownloadState.class);
    public static Type getType() {
        return gtype;
    }
    public static GDownloadState fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> NONE;
            case 1 -> PENDING;
            case 2 -> DOWNLOADING;
            case 3 -> DOWNLOADED;
            default -> throw new IllegalArgumentException("Invalid download state: " + ordinal);
        };
    }
}
