package com.github.subsound.ui.components;

import com.github.subsound.ui.models.GDownloadState;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Orientation;

public class SongDownloadStatusIcon extends Box {
    private final String iconName = "folder-download-symbolic";
    private volatile GDownloadState currentState;

    private final Image icon;
    public SongDownloadStatusIcon() {
        super(Orientation.HORIZONTAL, 2);
        this.icon = Image.fromIconName(Icons.FolderDownload.getIconName());
        this.append(this.icon);
    }

    public void updateDownloadState(GDownloadState state) {
        if (state == this.currentState) {
            return;
        }
        this.currentState = state;
        Utils.runOnMainThread(() -> {
            this.icon.setVisible(true);
            this.icon.removeCssClass(Classes.colorSuccess.className());
            switch (this.currentState) {
                case PENDING -> {
                    this.icon.setFromIconName(Icons.PAUSE.getIconName());
                    this.icon.setTooltipText("Download pending");
                }
                case DOWNLOADED -> {
                    this.icon.setFromIconName(Icons.FolderDownload.getIconName());
                    this.icon.setTooltipText("Available offline");
                    this.icon.addCssClass(Classes.colorSuccess.className());
                }
                case DOWNLOADING -> {
                    this.icon.setFromIconName(Icons.ContentLoadingSymbolic.getIconName());
                    this.icon.setTooltipText("Downloading...");
                }
                case CACHED -> {
                    this.icon.setFromIconName(Icons.FolderDownload.getIconName());
                    this.icon.setTooltipText("Cached - available offline");
                }
                case NONE -> this.icon.setVisible(false);
            }
        });
    }
}
