package io.github.jwharm.javagi.examples.playsound.views.components;

import org.gnome.gtk.*;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class LoadingSpinner extends Box {
    public LoadingSpinner() {
        super(Orientation.VERTICAL, 8);
        var spinner = Spinner.builder().setSpinning(true).build();
        this.append(spinner);
        this.append(Label.builder().setLabel("Loading...").setCssClasses(cssClasses("heading")).build());
    }

    public static LoadingSpinner fullscreen() {
        var loadingSpinner = new LoadingSpinner();
        loadingSpinner.setHexpand(true);
        loadingSpinner.setVexpand(true);
        loadingSpinner.setHalign(Align.CENTER);
        loadingSpinner.setValign(Align.CENTER);
        return loadingSpinner;
    }
}
