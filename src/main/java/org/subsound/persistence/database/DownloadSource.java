package org.subsound.persistence.database;

/**
 * Why a song is in the download set — recorded in {@code download_queue.source}.
 *
 * <p>{@link #ADDED_BY_USER} is sticky: an {@code ADDED_BY_USER} row is never downgraded to
 * {@link #PLAYLIST_SYNC}, and a manual download of a playlist-synced song escalates it to
 * {@code ADDED_BY_USER}. This is groundwork for a future reverse-sync that may demote
 * {@code PLAYLIST_SYNC} songs back to {@code CACHED} when they leave the playlist that pulled
 * them in, while leaving {@code ADDED_BY_USER} songs alone.
 */
public enum DownloadSource {
    /** The user explicitly asked to download this song. */
    ADDED_BY_USER,
    /** Pulled in by an offline-marked playlist (or the Starred playlist) sync. */
    PLAYLIST_SYNC,
}
