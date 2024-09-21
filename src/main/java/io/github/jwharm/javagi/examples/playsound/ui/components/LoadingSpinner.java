package io.github.jwharm.javagi.examples.playsound.ui.components;

import org.gnome.gtk.*;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class LoadingSpinner extends Box {
    private final Label label;

    public LoadingSpinner() {
        this("Loading...");
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
