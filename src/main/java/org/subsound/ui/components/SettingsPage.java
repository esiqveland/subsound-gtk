package org.subsound.ui.components;

import org.subsound.app.state.PlayerAction;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.SelectionMode;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.subsound.ui.components.Classes.boxedList;
import static org.subsound.ui.components.Classes.destructiveAction;
import static org.gnome.gtk.Orientation.VERTICAL;

public class SettingsPage extends Box {

    private final ServerConfigForm form;
    private final Button clearSongCacheButton;
    private final Button clearThumbnailCacheButton;

    public SettingsPage(
            @Nullable ServerConfigForm.SettingsInfo settingsInfo,
            Path dataDir,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);

        this.form = new ServerConfigForm(settingsInfo, dataDir, onAction);

        this.clearSongCacheButton = Button.builder()
                .setLabel("Clear song cache")
                .setCssClasses(destructiveAction.add())
                .build();
        this.clearSongCacheButton.onClicked(() ->
                onAction.apply(new PlayerAction.ClearSongCache()));

        this.clearThumbnailCacheButton = Button.builder()
                .setLabel("Clear thumbnail cache")
                .setCssClasses(destructiveAction.add())
                .build();
        this.clearThumbnailCacheButton.onClicked(() ->
                onAction.apply(new PlayerAction.ClearThumbnailCache()));

        var cacheListBox = ListBox.builder().setSelectionMode(SelectionMode.NONE).setCssClasses(boxedList.add()).build();
        cacheListBox.append(clearSongCacheButton);
        cacheListBox.append(clearThumbnailCacheButton);

        this.append(form);
        this.append(cacheListBox);
    }

    public void setSettingsInfo(@Nullable ServerConfigForm.SettingsInfo s) {
        this.form.setSettingsInfo(s);
    }
}
