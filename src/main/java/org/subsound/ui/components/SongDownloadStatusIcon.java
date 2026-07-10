package org.subsound.ui.components;

import org.gnome.gtk.Align;
import org.gnome.gtk.Inscription;
import org.subsound.ui.models.GDownloadState;
import static org.subsound.i18n.I18n.tr;

/**
 * Lightweight download-state indicator. Uses {@link Inscription} (GTK4's high-performance
 * text-render-only widget) with Unicode glyphs instead of {@code Image} + icon-theme lookups.
 */
public class SongDownloadStatusIcon extends Inscription {
    // consider: U+2713 + U+20DD = ✓⃝
    private static final String GLYPH_DOWNLOADED = "\u2713\u20DD";   //  ✓⃝
    private static final String GLYPH_CACHED = "\u2713";   //
    private static final String GLYPH_PENDING = "\u23F8";      // ⏸
    private static final String GLYPH_DOWNLOADING = "\uD83D\uDDD8"; // 🗘 (U+1F5D8 surrogate pair)

    private volatile GDownloadState currentState;

    public SongDownloadStatusIcon() {
        super();
        // Char-based sizing didn't accommodate combining/emoji glyphs (✓⃝ and 🗘 render larger
        // than ASCII). Reserve a fixed pixel box and center the text inside it.
        this.setSizeRequest(24, 24);
        this.setXalign(0.5f);
        this.setYalign(0.5f);
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);
        // Glyph is set on first updateDownloadState() during bind. Starting invisible
        // (opacity=0) means no initial setText is needed.
        this.setOpacity(0);
    }

    public void updateDownloadState(GDownloadState state) {
        if (state == this.currentState) {
            return;
        }
        this.currentState = state;
        this.removeCssClass(Classes.colorSuccess.className());
        switch (state) {
            case DOWNLOADED -> {
                this.setText(GLYPH_DOWNLOADED);
                this.setTooltipText(tr("Available offline"));
                this.addCssClass(Classes.colorSuccess.className());
                this.setOpacity(1);
            }
            case CACHED -> {
                this.setText(GLYPH_CACHED);
                this.setTooltipText(tr("Cached - available offline"));
                this.setOpacity(1);
            }
            case PENDING -> {
                this.setText(GLYPH_PENDING);
                this.setTooltipText(tr("Download pending"));
                this.setOpacity(1);
            }
            case DOWNLOADING -> {
                this.setText(GLYPH_DOWNLOADING);
                this.setTooltipText(tr("Downloading..."));
                this.setOpacity(1);
            }
            case NONE -> {
                this.setTooltipText("");
                this.setOpacity(0);
            }
        }
    }
}
