package org.subsound.ui.components;

import org.gnome.gtk.*;

import static org.subsound.utils.Utils.cssClasses;
import static org.subsound.i18n.I18n.tr;

public class LoadingSpinner extends Box {
    private final Label label;

    public LoadingSpinner() {
        this(tr("Loading..."));
    }
    public LoadingSpinner(String text) {
        super(Orientation.VERTICAL, 8);
        this.label = Label.builder().setLabel(text).setCssClasses(cssClasses("heading")).build();
        var spinner = Spinner.builder().setSpinning(true).build();
        this.append(spinner);
        if (text != null && !text.isEmpty()) {
            this.append(label);
        }
    }

    public static LoadingSpinner fullscreen(String text) {
        var loadingSpinner = new LoadingSpinner(text);
        loadingSpinner.setHexpand(true);
        loadingSpinner.setVexpand(true);
        loadingSpinner.setHalign(Align.CENTER);
        loadingSpinner.setValign(Align.CENTER);
        return loadingSpinner;
    }
}
