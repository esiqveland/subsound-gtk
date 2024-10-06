package org.mpris.MediaPlayer2;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

import java.util.List;
import java.util.Map;

/** [https://specifications.freedesktop.org/mpris-spec/latest/Track_List_Interface.html]
 *
 * ## Properties
 * - Tracks		    ao  Read only
 * - CanEditTracks	b   Read only
 * */
@DBusInterfaceName("org.mpris.MediaPlayer2.TrackList")
public interface TrackList extends DBusInterface {
    record TrackId(String trackid){}

    class TrackListReplaced extends DBusSignal {
        private final DBusPath path;
        private final List<TrackId> tracks;
        private final TrackId currentTrackId;

        /** Indicates that the entire tracklist has been replaced.
         *
         * It is left up to the implementation to decide when a change to the track list is invasive enough that this signal should be emitted instead of a series of TrackAdded and TrackRemoved signals.
         *
         * @param path The path to the object this is emitted from.
         * @param tracks new content of the tracklist
         * @param currentTrackId The identifier of the track to be considered as current.
         * `/org/mpris/MediaPlayer2/TrackList/NoTrack` indicates that there is no current track. */
        public TrackListReplaced(DBusPath path, List<TrackId> tracks, TrackId currentTrackId) throws DBusException {
            super(path.getPath(), tracks.stream().map(TrackId::trackid).toList(), currentTrackId.trackid());
            this.path = path;
            this.tracks = tracks;
            this.currentTrackId = currentTrackId;
        }
    }

    class TrackAdded extends DBusSignal {
        private final DBusPath path;
        private final Map<String, Variant<?>> metadata;
        private final TrackId afterTrack;

        /** Indicates that a track has been added to the track list.
         *
         * @param path The path to the object this is emitted from.
         * @param metadata The metadata of the newly added item. This must include a mpris:trackid entry.
         * @param afterTrack The identifier of the track after which the new track was inserted.
         * The path `/org/mpris/MediaPlayer2/TrackList/NoTrack` indicates that the track was inserted at the start of the track list. */
        public TrackAdded(DBusPath path, Map<String, Variant<?>> metadata, TrackId afterTrack) throws DBusException {
            super(path.getPath(), metadata, afterTrack.trackid());
            this.path = path;
            this.metadata = metadata;
            this.afterTrack = afterTrack;
        }
    }

    class TrackRemoved extends DBusSignal{
        private final DBusPath path;
        private final TrackId trackId;

        /** Indicates that a track has been removed from the track list.
         *
         * @param path The path to the object this is emitted from.
         * @param trackId The identifier of the track being removed. */
        public TrackRemoved(DBusPath path, TrackId trackId) throws DBusException {
            super(path.getPath(), trackId.trackid());
            this.path = path;
            this.trackId = trackId;
        }
    }

    class TrackMetadataChanged extends DBusSignal {
        private final DBusPath path;
        private final TrackId trackId;
        private final Map<String, Variant<?>> metadata;

        /** Indicates that the metadata of a track in the tracklist has changed.
         *
         * This may indicate that a track has been replaced, in which case the mpris:trackid metadata entry is different from the TrackId argument.
         *
         * @param path The path to the object this is emitted from.
         * @param trackId The id of the track which metadata has changed. If the track id has changed, this will be the old value.
         * @param metadata metadata of the new track. This must include a mpris:trackid entry. */
        public TrackMetadataChanged(DBusPath path, TrackId trackId, Map<String, Variant<?>> metadata) throws DBusException {
            super(path.getPath(), trackId.trackid(), metadata);
            this.path = path;
            this.trackId = trackId;
            this.metadata = metadata;
        }
    }

    List<Map<String, Variant<?>>> GetTracksMetadata(List<String> trackIds);
    void AddTrack(String uri, String afterTrackId, boolean setAsCurrent);
    void RemoveTrack(String trackId);
    void GoTo(String trackId);
}