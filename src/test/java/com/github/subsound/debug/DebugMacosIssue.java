package com.github.subsound.debug;

import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Orientation;

public class DebugMacosIssue {
    public static void main(String[] args) {
        Application app = new Application("com.macos.example", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> onActivate(app));
        app.onShutdown(() -> {
            System.out.println("app.onShutdown: exit");
        });
        app.run(args);

    }

    private static void onActivate(Application app) {
        // Pack everything together, and show the window
        Box box = Box.builder().setSpacing(10).setOrientation(Orientation.VERTICAL).build();
        Button btn = Button.builder().setLabel("Hello").build();
        btn.onClicked(() -> {
            System.out.println("btn.onClicked: HI!");
        });
        box.append(btn);
        var window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(1000)
                .setDefaultHeight(700)
                .setContent(box)
                .build();

        window.present();
    }
}
