package com.github.subsound.ui.components;

import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.jetbrains.annotations.Nullable;

public class ClickLabel extends Label {
    private final Runnable onClick;
    private final GestureClick gestureClick;

    public ClickLabel(@Nullable String text, Runnable onClick) {
        super(text);
        this.onClick = onClick;
        //this.addCssClass(Classes.card.className());
        this.addCssClass(Classes.clickLabel.className());
        this.gestureClick = GestureClick.builder().build();
        this.addController(gestureClick);
        this.onMap(() -> {
            gestureClick.onReleased((int nPress, double x, double y) -> {
                System.out.println("gestureClick.onReleased: " + nPress);
                this.onClick.run();
            });
        });
        this.onUnmap(() -> {});
    }
}
