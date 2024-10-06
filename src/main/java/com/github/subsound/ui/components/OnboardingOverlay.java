package com.github.subsound.ui.components;

import com.github.subsound.utils.Utils;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.ToolbarView;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Widget;

public class OnboardingOverlay extends Overlay {
    private final SettingsPage settingsPage;
    private final Widget child;
    private final Box centerBox;
    private final Box settingsBox;
    private final HeaderBar headerBar;
    private final ToolbarView toolbarView;

    public OnboardingOverlay(SettingsPage settingsPage, Widget child) {
        super();
        this.settingsPage = settingsPage;
        this.child = child;
        this.centerBox = Utils.borderBox(Orientation.VERTICAL, 10).build();
        this.centerBox.addCssClass(Classes.transparent.className());
        this.settingsBox = Utils.borderBox(Orientation.VERTICAL, 14).setHalign(Align.CENTER).setValign(Align.CENTER).setHexpand(false).setVexpand(true).build();
        this.settingsBox.addCssClass(Classes.shadow.className());
        this.settingsBox.addCssClass(Classes.background.className());
        this.centerBox.append(this.settingsBox);
        this.headerBar = new HeaderBar();
        this.toolbarView = new ToolbarView();
        this.headerBar.setTitleWidget(Label.builder().setLabel("Login").setCssClasses(Classes.title1.add()).build());
        this.toolbarView.addTopBar(this.headerBar);
        this.toolbarView.setContent(this.settingsPage);
        this.settingsBox.append(this.toolbarView);
        this.addOverlay(this.centerBox);
        this.child.addCssClass(Classes.blurred.className());
        this.setChild(child);
        this.setChildVisible(true);
    }
}
