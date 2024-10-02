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

public class DebugGtkScaleIssue {
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
        Button btn = Button.builder().setLabel("Hello").build();
        btn.onClicked(() -> {
            System.out.println("btn.onClicked: HI!");
            cancelled.set(!cancelled.get());
        });

        var scale1 = Scale.builder().setOrientation(Orientation.HORIZONTAL).build();
        scale1.setRange(0, 100);
        scale1.setShowFillLevel(true);
        scale1.setFillLevel(1);

        var fill = new AtomicInteger();
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            int i = fill.addAndGet(1);
            if (i > 99) {
                fill.set(0);
            }
            if (cancelled.get()) {
                return;
            }
            GLib.idleAddOnce(() -> {
                scale1.setFillLevel(fill.get());
                scale1.setShowFillLevel(true);
            });
        }, 1000, 100, TimeUnit.MILLISECONDS);

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
