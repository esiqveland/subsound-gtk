package org.subsound.ui.components;

import org.subsound.utils.ImageUtils;
import org.gnome.gdkpixbuf.Pixbuf;

import java.nio.file.Path;

import static org.subsound.utils.Utils.mustReadBytes;

// https://specifications.freedesktop.org/icon-naming-spec/latest/
// https://gitlab.gnome.org/GNOME/adwaita-icon-theme/-/tree/master/Adwaita/symbolic?ref_type=heads
public enum ImageIcons {
    SubsoundLarge(Path.of("icons/generated/icon-256.png")),
    SubsoundSmall(Path.of("icons/generated/icon-64.png")),
    ;

    private final Pixbuf pixbuf;
    ImageIcons(Path path) {
        byte[] bytes = mustReadBytes(path.toString());
        this.pixbuf = ImageUtils.readPixbuf(bytes);
    }

    public Pixbuf getPixbuf() {
        return this.pixbuf;
    }
}
