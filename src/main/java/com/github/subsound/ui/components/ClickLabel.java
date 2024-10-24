package com.github.subsound.ui.components;

import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

public class ClickLabel extends Label {
    private final Runnable onClick;
    private final GestureClick gestureClick;

    public ClickLabel(@Nullable String text, Runnable onClick) {
        super(text);
        this.onClick = onClick;
        this.registerClick();
        //this.addCssClass(Classes.card.className());
        this.addCssClass(Classes.clickLabel.className());
        this.gestureClick = GestureClick.builder().build();
        this.addController(gestureClick);
        this.onMap(() -> {
            var ref = gestureClick.onReleased((int nPress, double x, double y) -> {
                System.out.println("gestureClick.onReleased: " + nPress);
                this.onClick.run();
            });
        });
        this.onUnmap(() -> {
        });

    }

    private void registerClick() {
    }
}
