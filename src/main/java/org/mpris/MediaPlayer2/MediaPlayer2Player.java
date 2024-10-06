package org.mpris.MediaPlayer2;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.Variant;

/** [https://specifications.freedesktop.org/mpris-spec/latest/Player_Interface.html]
 *
 * ## Properties
 * - PlaybackStatus	s (Playback_Status) 	Read only
 * - LoopStatus		s (Loop_Status)	        Read/Write	(optional)
 * - Rate		    d (Playback_Rate)	    Read/Write
 * - Shuffle		b	                    Read/Write	(optional)
 * - Metadata		a{sv} (Metadata_Map)	Read only
 * - Volume		    d (Volume)	            Read/Write
 * - Position		x (Time_In_Us)	        Read only
 * - MinimumRate	d (Playback_Rate)	    Read only
 * - MaximumRate	d (Playback_Rate)	    Read only
 * - CanGoNext		b	                    Read only
 * - CanGoPrevious	b	                    Read only
 * - CanPlay		b	                    Read only
 * - CanPause		b	                    Read only
 * - CanSeek		b	                    Read only
 * - CanControl		b	                    Read only
 * */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
public interface MediaPlayer2Player extends DBusInterface {
    public static class Seeked extends DBusSignal {
        private final DBusPath path;
        private final long position;

        public Seeked(DBusPath path, long position) throws DBusException {
            super(path.getPath(), position);
            this.path = path;
            this.position = position;
        }
    }

    void Next();
    void Previous();
    void Pause();
    void PlayPause();
    void Stop();
    void Play();
    void Seek(long x);
    void OpenUri(String uri);
    void SetPosition(String trackId, long position);

    enum PlaybackStatus {
        Playing, Paused, Stopped;

        public Variant<String> variant() {
            return new Variant<>(this.toString());
        }
    }

    enum LoopStatus {
        None, Track, Playlist;

        public Variant<String> variant() {
            return new Variant<>(this.toString());
        }
    }
}