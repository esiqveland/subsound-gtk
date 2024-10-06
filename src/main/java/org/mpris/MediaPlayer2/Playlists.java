package org.mpris.MediaPlayer2;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.annotations.Position;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.jetbrains.annotations.Nullable;

import java.util.List;


/** [https://specifications.freedesktop.org/mpris-spec/latest/Playlists_Interface.html]
 *
 * ## Properties
 * ```
 * PlaylistCount   u                               Read only - The number of playlists available.
 * Orderings       as (Array of PlaylistOrdering)  Read only - The available orderings. At least one must be offered.
 * ActivePlaylist  (b(oss)) (MaybePlaylist)        Read only - The currently-active playlist.
 * ```
 * */
@DBusInterfaceName("org.mpris.MediaPlayer2.Playlists")
public interface Playlists extends DBusInterface {
    class PlaylistChanged extends DBusSignal {
        private final DBusPath path;
        private final Playlist playlist;

        public PlaylistChanged(DBusPath path, Playlist playlist) throws DBusException {
            super(path.getPath(), playlist);
            this.path = path;
            this.playlist = playlist;
        }
    }

    void ActivatePlaylist(DBusInterface playlistId);
    List<Playlist> GetPlaylists(int index, int max_count, String order, boolean reverse_order);

    /** # Playlist — (oss)
     * A data structure describing a playlist.
     * ## Properties
     * ```
     * Id   o - A unique identifier for the playlist. This should remain the same if the playlist is renamed.
     * Name s - The name of the playlist, typically given by the user.
     * Icon s - The URI of an (optional) icon.
     * ```
     * */
    class Playlist extends Struct {
        // PlaylistId
        @Position(0)
        private final String id;
        @Position(1)
        private final String name;
        @Position(2)
        @Nullable private final String icon;

        public Playlist(String id, String name, @Nullable String icon) {
            this.id = id;
            this.name = name;
            this.icon = icon;
        }
    }

    /** # Maybe_Playlist — (b(oss))
     * A data structure describing a playlist, or nothing.
     * ## Properties
     * ```
     * Valid    b                - Whether this structure refers to a valid playlist.
     * Playlist (oss) (Playlist) - The playlist, providing Valid is true, otherwise undefined.
     * ```
     * */
    class MaybePlaylist extends Struct {
        @Position(0)
        private final boolean valid;
        @Position(1)
        private final Playlist playlist;

        /** Creates an empty MaybePlaylist. */
        public MaybePlaylist() {
            this(false, new Playlist("/", "", null));
        }
        /** Creates a valid MaybePlaylist containing the given [playlist]. */
        public MaybePlaylist(Playlist playlist) {
            this(true, playlist);
        }

        public MaybePlaylist(boolean isValid, Playlist playlist) {
            this.valid = isValid;
            this.playlist = playlist;
        }
    }

    /** # Playlist_Ordering — s
     * Specifies the ordering of returned playlists. */
    enum PlaylistOrdering {
        /** Alphabetical ordering by name, ascending. */
        Alphabetical("Alphabetical"),
        /** Ordering by creation date, oldest first. */
        CreationDate("Created"),
        /** Ordering by last modified date, oldest first. */
        ModifiedDate("Modified"),
        /** Ordering by date of last playback, oldest first. */
        LastPlayDate("Played"),
        /** A user-defined ordering. */
        UserDefined("User");

        private final String value;

        PlaylistOrdering(String value) {
            this.value = value;
        }
    }
}

