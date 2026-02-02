package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient.SongInfo;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;

import java.lang.foreign.MemorySegment;

public class GQueueItem extends GObject {
    public static final Type gtype = Types.register(GQueueItem.class);

    public enum QueueKind {
        USER_ADDED,
        AUTOMATIC,
    }

    private GSongInfo gSongInfo;
    private QueueKind queueKind = QueueKind.AUTOMATIC;

    public static Type getType() {
        return gtype;
    }

    public GQueueItem(MemorySegment address) {
        super(address);
    }

    public GSongInfo getSongInfo() {
        return gSongInfo;
    }
    public SongInfo songInfo() {
        return this.gSongInfo.getSongInfo();
    }

    @Property
    public String getId() {
        return gSongInfo != null ? gSongInfo.getSongInfo().id() : null;
    }

    public QueueKind queueKind() {
        return queueKind;
    }

    @Property
    // should be available as 'is-user-queued' in GTK component
    public boolean getIsUserQueued() {
        return queueKind == QueueKind.USER_ADDED;
    }

    public static GQueueItem newInstance(SongInfo value) {
        return newInstance(GSongInfo.newInstance(value), QueueKind.AUTOMATIC);
    }

    public static GQueueItem newInstance(SongInfo value, QueueKind queueKind) {
        return newInstance(GSongInfo.newInstance(value), queueKind);
    }

    public static GQueueItem newInstance(GSongInfo gSongInfo, QueueKind queueKind) {
        GQueueItem instance = GObject.newInstance(gtype);
        instance.gSongInfo = gSongInfo;
        instance.queueKind = queueKind;
        return instance;
    }
}
