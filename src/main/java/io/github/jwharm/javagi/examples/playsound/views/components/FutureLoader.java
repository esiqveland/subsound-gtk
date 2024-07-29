package io.github.jwharm.javagi.examples.playsound.views.components;


import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FutureLoader<T, R extends Widget> extends Box {
    private final CompletableFuture<T> future;
    private final Function<T, R> builder;
    private final StatusPage statusPage;

    public FutureLoader(CompletableFuture<T> future, Function<T, R> builder) {
        super(Orientation.VERTICAL, 0);
        this.future = future;
        this.builder = builder;
        this.statusPage = StatusPage.builder().setChild(LoadingSpinner.fullscreen()).build();
        this.append(statusPage);

        future.whenCompleteAsync((value, exception) -> {
            if (exception != null) {
                Utils.runOnMainThread(() -> {
                    this.remove(statusPage);
                    this.append(StatusPage.builder().setChild(new Label("Error loading: %s".formatted(exception.getMessage()))).build());
                });
            } else {
                var widget = this.builder.apply(value);
                Utils.runOnMainThread(() -> {
                    this.remove(statusPage);
                    this.append(widget);
                });
            }
        });
//        future.whenComplete()
//        switch (this.future.state()) {
//            case RUNNING ->
//        }

//        this.future.handle()

    }
}
