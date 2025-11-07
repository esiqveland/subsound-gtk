package com.github.subsound.debug;

import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.HeaderBar;
import org.gnome.gdk.Gdk;
import org.gnome.gio.ApplicationFlags;
import org.gnome.glib.GLib;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Scale;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DebugGtkMemoryIssue {
    public static void main(String[] args) {
        Application app = new Application("com.scale.example", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> onActivate(app));
        app.onShutdown(() -> {
            System.out.println("app.onShutdown: exit");
        });
        app.run(args);

    }

    private static void onActivate(Application app) {
        var cancelled = new AtomicBoolean(false);
        Button btn = Button.builder().setLabel("Cancel worker").build();
        btn.onClicked(() -> {
            System.out.println("btn.onClicked: cancel!");
            cancelled.set(!cancelled.get());
        });

        var scale1 = Scale.builder().setOrientation(Orientation.HORIZONTAL).build();
        scale1.setRange(0, 100);
        scale1.setShowFillLevel(true);
        scale1.setFillLevel(1);

        var counter = new AtomicInteger();
        var fill = new AtomicInteger();
        Thread.startVirtualThread(() -> {
            while (true) {
                if (cancelled.get()) {
                    System.out.println("worker: canceled after %d".formatted(counter.get()));
                    return;
                }
                var count = counter.addAndGet(1);
                if (count % 10 == 0) {
                    System.out.println("counter: %d".formatted(count));
                }
                int i = fill.addAndGet(1);
                var fillLevel = i % 100;

//                GLib.idleAddOnce(() -> {
//                    scale1.setFillLevel(fill.get());
//                    scale1.setShowFillLevel(true);
//                });
                GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
                    scale1.setFillLevel(fillLevel);
                    // changing fill level does not always redraw the scale component,
                    // but scale.getAdjustment().emitValueChanged() forces a redraw:
                    scale1.getAdjustment().emitValueChanged();
                    return GLib.SOURCE_REMOVE;
                });

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);  
                }
            }
        });

        var toolbar = new HeaderBar();
        // Pack everything together, and show the window
        Box box = Box.builder().setSpacing(10).setOrientation(Orientation.VERTICAL).build();
        box.append(toolbar);
        box.append(btn);
        box.append(scale1);

        var window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(1000)
                .setDefaultHeight(700)
                .setContent(box)
                .build();

        window.present();
    }

}
