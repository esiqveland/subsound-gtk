package org.subsound.ui.components;

import org.gnome.gdk.Texture;
import org.javagi.base.GErrorException;
import org.subsound.utils.Lazy;

import java.nio.file.Path;

import static org.subsound.utils.Utils.mustReadBytes;

// https://specifications.freedesktop.org/icon-naming-spec/latest/
// https://gitlab.gnome.org/GNOME/adwaita-icon-theme/-/tree/master/Adwaita/symbolic?ref_type=heads
public enum ImageIcons {
    SubsoundLarge(Path.of("icons/generated/icon-256.png")),
    SubsoundSmall(Path.of("icons/generated/icon-64.png")),
    ;

    private final Lazy<Texture> pixbuf;
    ImageIcons(Path path) {
        this.pixbuf = Lazy.of(() -> {
            try {
                byte[] bytes = mustReadBytes(path.toString());
                return Texture.fromBytes(bytes);
            } catch (GErrorException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public Texture getTexture() {
        return this.pixbuf.get();
    }
}
