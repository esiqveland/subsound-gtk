package io.github.jwharm.javagi.examples.playsound.ui.components;

import org.gnome.gtk.Snapshot;
import org.gnome.gtk.Widget;

import java.lang.foreign.MemorySegment;

public class AlbumCoverPhoto extends Widget {
    /**
     * Create a Widget proxy instance for the provided memory address.
     *
     * @param address the memory address of the native object
     */
    public AlbumCoverPhoto(MemorySegment address) {
        super(address);
    }

    @Override
    protected void snapshot(Snapshot snapshot) {

        //snapshot.appendScaledTexture();
    }
}
