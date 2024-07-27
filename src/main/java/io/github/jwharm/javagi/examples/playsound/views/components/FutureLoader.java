package io.github.jwharm.javagi.examples.playsound.views.components;


import org.gnome.adw.StatusPage;
import org.gnome.gtk.Box;
import org.gnome.gtk.Orientation;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FutureLoader<T, R> extends Box {
    private final CompletableFuture<T> future;

    public FutureLoader(CompletableFuture<T> future, Function<T, R> builder) {
        super(Orientation.VERTICAL, 0);
        this.future = future;

//        this.future.handle()
        StatusPage statusPage = StatusPage.builder().build();

    }
}
