package io.github.jwharm.javagi.examples.playsound.ui.components;


import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.adw.StatusPage;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.github.jwharm.javagi.examples.playsound.app.state.AppManager.ASYNC_EXECUTOR;

public class FutureLoader<T, WIDGET extends Widget> extends Box {
    private static final Logger log = LoggerFactory.getLogger(FutureLoader.class);
    private final CompletableFuture<T> future;
    private final Function<T, WIDGET> builder;
    private final StatusPage statusPage;
    private WIDGET widget;

    public FutureLoader(CompletableFuture<T> future, Function<T, WIDGET> builder) {
        super(Orientation.VERTICAL, 0);
        this.future = future;
        this.builder = builder;
        this.statusPage = StatusPage.builder().setChild(LoadingSpinner.fullscreen("Loading...")).build();
        this.append(statusPage);

        this.future.whenCompleteAsync((value, exception) -> {
            if (exception != null) {
                log.warn("error loading: {}", exception.getMessage(), exception);
                Utils.runOnMainThread(() -> {
                    this.remove(statusPage);
                    this.append(StatusPage.builder().setChild(new Label("Error loading: %s".formatted(exception.getMessage()))).build());
                });
            } else {
                var widget = this.builder.apply(value);
                this.widget = widget;
                log.info("FutureLoader hello {}", widget.getClass().getName());
                Utils.runOnMainThread(() -> {
                    this.remove(statusPage);
                    this.append(widget);
                });
            }
        }, ASYNC_EXECUTOR)
                .whenCompleteAsync((v, ex) -> log.error("error: ", ex));
    }

    public Optional<WIDGET> getMainWidget() {
        return Optional.ofNullable(this.widget);
    }
}
