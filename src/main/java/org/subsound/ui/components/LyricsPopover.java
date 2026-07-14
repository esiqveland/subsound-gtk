package org.subsound.ui.components;

import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CenterBox;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.Window;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.lyrics.LyricsResult;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.subsound.i18n.I18n.tr;

/**
 * Popover showing lyrics for the currently playing song. The line list, highlight
 * and auto-scroll behavior live in {@link LyricsLinesView}; this class only adds
 * the popover chrome (header, expand-to-fullscreen button, window height clamp).
 */
public class LyricsPopover extends Popover {
    private final LyricsLinesView lines;

    public LyricsPopover(
            Function<SongInfo, Optional<LyricsResult>> lyricsProvider,
            Consumer<Duration> onSeek,
            Runnable onExpand
    ) {
        super();
        this.lines = new LyricsLinesView(lyricsProvider, onSeek, new LyricsLinesView.Config(
                Classes.lyricsLine,
                40,
                300,
                800,
                380,
                380,
                true,
                false
        ));

        var headerLabel = Label.builder()
                .setLabel(tr("Lyrics"))
                .setMarginTop(8)
                .setMarginBottom(8)
                .build();
        headerLabel.addCssClass(Classes.heading.className());

        var expandButton = Button.builder()
                .setIconName(Icons.Fullscreen.getIconName())
                .setTooltipText(tr("Show lyrics fullscreen"))
                .build();
        expandButton.addCssClass(Classes.flat.className());
        expandButton.onClicked(() -> {
            this.popdown();
            onExpand.run();
        });

        var header = CenterBox.builder()
                .setCenterWidget(headerLabel)
                .setEndWidget(expandButton)
                .build();

        var content = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(4)
                .build();
        content.append(header);
        content.append(lines);

        this.setChild(content);
        this.setPosition(PositionType.TOP);

        this.onShow(() -> {
            updateMaxHeight();
            lines.loadForCurrentSong();
        });
    }

    /**
     * Called from AppManager state changes (virtual thread). Diffs by song id and
     * clears stale lyrics; refetches immediately only while the popover is open.
     */
    public void setNowPlaying(Optional<SongInfo> song) {
        this.lines.setNowPlaying(song);
    }

    /**
     * Called on the GTK main thread (PlayerBar tick and position snaps).
     * Cheap no-op while the popover is closed or the lyrics are unsynced.
     */
    public void updatePosition(Duration position) {
        this.lines.updatePosition(position);
    }

    private void updateMaxHeight() {
        var root = this.getRoot();
        if (root instanceof Window window) {
            int windowHeight = window.getHeight();
            if (windowHeight > 0) {
                int maxHeight = Math.max(400, windowHeight - 150);
                lines.setMaxContentHeight(maxHeight);
            }
        }
    }
}
