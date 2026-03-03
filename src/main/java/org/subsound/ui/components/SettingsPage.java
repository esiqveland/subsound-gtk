package org.subsound.ui.components;

import org.gnome.adw.ActionRow;
import org.gnome.adw.ButtonRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.PreferencesGroup;
import org.gnome.gtk.Align;
import org.subsound.app.state.PlayerAction;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.SelectionMode;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.subsound.ui.components.Classes.boxedList;
import static org.subsound.ui.components.Classes.destructiveAction;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.utils.Utils.borderBox;

public class SettingsPage extends Box {

    private final ServerConfigForm form;
    private final ButtonRow clearSongCacheButton;
    private final ButtonRow clearThumbnailCacheButton;
    private final PreferencesGroup localSettings;
    private final Box centerBox;

    public SettingsPage(
            @Nullable ServerConfigForm.SettingsInfo settingsInfo,
            Path dataDir,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);
        this.clearSongCacheButton = ButtonRow.builder().setTitle("Clear song cache").build();
        this.clearSongCacheButton.addCssClass(destructiveAction.className());
        this.clearSongCacheButton.onActivated(() -> onAction.apply(new PlayerAction.ClearSongCache()));

        this.clearThumbnailCacheButton = ButtonRow.builder().setTitle("Clear thumbnail cache").build();
        this.clearThumbnailCacheButton.addCssClass(destructiveAction.className());
        this.clearThumbnailCacheButton.onActivated(() -> onAction.apply(new PlayerAction.ClearThumbnailCache()));

        this.localSettings = new PreferencesGroup();
        this.localSettings.setTitle("Local settings");
        this.localSettings.setSeparateRows(false);
        this.localSettings.add(clearSongCacheButton);
        this.localSettings.add(clearThumbnailCacheButton);

        this.form = new ServerConfigForm(
                settingsInfo,
                dataDir,
                onAction
        );

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(this.form);
        this.centerBox.append(this.localSettings);

        var clamp = new Clamp();
        clamp.setMaximumSize(600);
        clamp.setChild(this.centerBox);
        this.append(clamp);
    }

    public void setSettingsInfo(@Nullable ServerConfigForm.SettingsInfo s) {
        this.form.setSettingsInfo(s);
    }
}
