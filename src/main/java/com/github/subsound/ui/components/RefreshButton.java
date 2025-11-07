package com.github.subsound.ui.components;

import org.gnome.gtk.Button;
import org.javagi.gobject.SignalConnection;

public class RefreshButton extends Button {
    private final Runnable onClick;
    private SignalConnection<ClickedCallback> signal;

    public RefreshButton(Runnable onClick) {
        super();
        this.onClick = onClick;
        //setLabel("Refresh");
        setLabel("Refresh");
        setIconName(Icons.RefreshView.getIconName());
        setTooltipText("Refresh");
        this.signal = this.onClicked(this.onClick::run);
    }

    @Override
    protected void dispose() {
        var sig = this.signal;
        if (sig != null) {
            sig.disconnect();
            this.signal = null;
        }
        super.dispose();
    }
}
