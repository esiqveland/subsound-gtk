package com.github.subsound.ui.components;

import com.github.subsound.utils.Utils;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.ToolbarView;
import org.gnome.gdk.Paintable;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Picture;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Widget;

public class OnboardingOverlay extends Overlay {
    private final SettingsPage settingsPage;
    private final Widget child;
    private final Box centerBox;
    private final Box settingsBox;
    private final HeaderBar headerBar;
    private final ToolbarView toolbarView;
    private final Box contentBox;
    private final Picture logo;

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
        this.logo = image(ImageIcons.SubsoundLarge);
        this.logo.setMarginBottom(20);
        this.headerBar = new HeaderBar();
        this.headerBar.setTitleWidget(Label.builder().setLabel("").setCssClasses(Classes.title1.add()).build());
        this.contentBox = Box.builder().setOrientation(Orientation.VERTICAL).setSpacing(0).build();
        this.contentBox.append(Label.builder().setLabel("Welcome").setJustify(Justification.CENTER).setCssClasses(Classes.titleLarge.add()).setMarginBottom(20).build());
        this.contentBox.append(this.logo);
        this.contentBox.append(Label.builder().setLabel("Login to your Subsonic Server to get started.").setJustify(Justification.CENTER).setCssClasses(Classes.bodyText.add()).setMarginBottom(10).build());
        this.contentBox.append(this.settingsPage);
        this.toolbarView = new ToolbarView();
        this.toolbarView.addTopBar(this.headerBar);
        this.toolbarView.setContent(this.contentBox);

        this.settingsBox.append(this.toolbarView);
        this.addOverlay(this.centerBox);
        this.child.addCssClass(Classes.blurred.className());
        this.setChild(child);
        this.setChildVisible(true);
    }

    private Picture image(ImageIcons imageIcons) {
        Texture texture = Texture.forPixbuf(imageIcons.getPixbuf());
        var p = Picture.forPaintable(texture);
        return p;
    }
}