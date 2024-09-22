package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;

public class BoxHolder<T extends Widget> extends Box {
    private T child;

    public BoxHolder() {
        this(null);
    }

    public BoxHolder(T child) {
        super(Orientation.VERTICAL, 0);
        this.child = child;
        if (this.child != null) {
            this.append(this.child);
        }
    }

    public void setChild(T nextChild) {
        this.child = nextChild;
        Utils.runOnMainThread(() -> {
            var child = this.child;
            var firstChild = this.getFirstChild();
            if (firstChild != null) {
                this.remove(firstChild);
            }
            if (child != null) {
                this.append(child);
            }
        });
    }

    public T getChild() {
        return child;
    }
}
