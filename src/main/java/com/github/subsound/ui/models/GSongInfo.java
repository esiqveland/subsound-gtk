package com.github.subsound.ui.models;

import com.github.subsound.integration.ServerClient;
import com.github.subsound.ui.views.StarredListView;
import org.javagi.gobject.types.Types;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;

import java.lang.foreign.MemorySegment;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    public static Type getType() {
        return gtype;
    }

    private ServerClient.SongInfo songInfo;

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
