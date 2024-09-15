package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;

public class BoxHolder extends Box {
    private Widget child;

    public BoxHolder() {
        this(null);
    }

    public BoxHolder(Widget child) {
        super(Orientation.VERTICAL, 0);
        this.child = child;
        if (this.child != null) {
            this.append(this.child);
        }
    }

    public void setChild(Widget nextChild) {
        Utils.runOnMainThread(() -> {
            var old = this.child;
            this.child = nextChild;
            if (old != null) {
                this.remove(old);
            }
            this.append(child);
        });
    }

    public Widget getChild() {
        return child;
    }
}
