package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient.SongInfo;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;

public class GQueueItem extends GObject {
    public static final Type gtype = Types.register(GQueueItem.class);

    public enum Signal {
        IS_CURRENT("is-current");

        private final String signal;

        Signal(String signal) {
            this.signal = signal;
        }

        public String getId() {
            return this.signal;
        }
    }

    private final AtomicBoolean isCurrent = new AtomicBoolean(false);
    private SongInfo songInfo;

    public static Type getType() {
        return gtype;
    }

    public GQueueItem(MemorySegment address) {
        super(address);
    }

    public SongInfo songInfo() {
        return songInfo;
    }

    @Property
    public String getId() {
        return songInfo != null ? songInfo.id() : null;
    }
    public String getTitle() {
        return songInfo != null ? songInfo.title() : null;
    }

    @Property
    public boolean getIsCurrent() {
        return isCurrent.get();
    }

    @Property
    public void setIsCurrent(boolean current) {
        if (this.isCurrent.compareAndSet(!current, current)) {
            this.notify(Signal.IS_CURRENT.signal);
        }
    }

    public static GQueueItem newInstance(SongInfo value) {
        GQueueItem instance = GObject.newInstance(gtype);
        instance.songInfo = value;
        return instance;
    }
}
