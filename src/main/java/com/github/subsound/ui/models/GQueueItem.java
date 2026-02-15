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
    private int originalOrder;   // Position when added to queue (for unshuffle)
    private int shuffleOrder;    // Random number for shuffle sorting

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

    public int getOriginalOrder() {
        return originalOrder;
    }

    public void setOriginalOrder(int order) {
        this.originalOrder = order;
    }

    public int getShuffleOrder() {
        return shuffleOrder;
    }

    public void setShuffleOrder(int order) {
        this.shuffleOrder = order;
    }

    @Property
    // should be available as 'is-user-queued' in GTK component
    public boolean getIsUserQueued() {
        return queueKind == QueueKind.USER_ADDED;
    }

    public static GQueueItem newInstance(GSongInfo gSongInfo, QueueKind queueKind) {
        return newInstance(gSongInfo, queueKind, 0);
    }

    public static GQueueItem newInstance(GSongInfo gSongInfo, QueueKind queueKind, int originalOrder) {
        GQueueItem instance = GObject.newInstance(gtype);
        instance.gSongInfo = gSongInfo;
        instance.queueKind = queueKind;
        instance.originalOrder = originalOrder;
        return instance;
    }
}
