package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.ui.views.StarredListView;
import org.javagi.gobject.types.Types;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    public enum Signal {
        IS_PLAYING("is-playing"),
        IS_FAVORITE("is-favorite");
        private final String signal;

        Signal(String signal) {
            this.signal = signal;
        }

        public String getId() {
            return this.signal;
        }
    }

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);

    public static Type getType() {
        return gtype;
    }

    private ServerClient.SongInfo songInfo;

    public GSongInfo setIsPlaying(boolean isPlaying) {
        this.isPlaying.set(isPlaying);
        this.notify(Signal.IS_PLAYING.signal);
        return this;
    }

    public GSongInfo(MemorySegment address) {
        super(address);
    }

    public ServerClient.SongInfo songInfo() {
        return songInfo;
    }

    public static GSongInfo newInstance(ServerClient.SongInfo value) {
        GSongInfo instance = GObject.newInstance(gtype);
        instance.songInfo = value;
        return instance;
    }
}
