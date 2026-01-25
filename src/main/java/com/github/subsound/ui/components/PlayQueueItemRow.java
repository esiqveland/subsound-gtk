package com.github.subsound.ui.components;

import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.models.GQueueItem;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class PlayQueueItemRow extends Box {
    private static final int TRACK_NUMBER_LABEL_CHARS = 4;

    private GQueueItem gQueueItem;
    private SongInfo songInfo;

    private final Label trackNumberLabel;
    private final Label titleLabel;
    private final Label artistLabel;
    private final Label durationLabel;

    private final AtomicReference<SignalConnection<?>> isCurrentSignal = new AtomicReference<>();
    private final AtomicReference<SignalConnection<?>> positionSignal = new AtomicReference<>();
    private final AtomicInteger index = new AtomicInteger(0);

    public PlayQueueItemRow() {
        super(HORIZONTAL, 8);

        this.setMarginTop(6);
        this.setMarginBottom(6);
        this.setMarginStart(8);
        this.setMarginEnd(8);

        // Track number label
        this.trackNumberLabel = infoLabel("", Classes.labelDim.add(Classes.labelNumeric));
        this.trackNumberLabel.setVexpand(false);
        this.trackNumberLabel.setValign(CENTER);
        this.trackNumberLabel.setJustify(Justification.RIGHT);
        this.trackNumberLabel.setSingleLineMode(true);
        this.trackNumberLabel.setWidthChars(TRACK_NUMBER_LABEL_CHARS);
        this.trackNumberLabel.setMaxWidthChars(TRACK_NUMBER_LABEL_CHARS);

        // Content box (title + subtitle)
        var contentBox = new Box(VERTICAL, 2);
        contentBox.setHalign(START);
        contentBox.setValign(CENTER);
        contentBox.setVexpand(true);
        contentBox.setHexpand(true);

        // Title label
        this.titleLabel = Label.builder()
                .setLabel("")
                .setHalign(Align.START)
                .setEllipsize(EllipsizeMode.END)
                .setMaxWidthChars(40)
                .build();
        this.titleLabel.setSingleLineMode(true);

        // Subtitle row (artist + duration)
        var subtitleBox = new Box(HORIZONTAL, 4);
        subtitleBox.setHalign(START);

        this.artistLabel = infoLabel("", Classes.labelDim.add(Classes.caption));
        this.artistLabel.setHalign(START);
        this.artistLabel.setSingleLineMode(true);
        this.artistLabel.setEllipsize(EllipsizeMode.END);
        this.artistLabel.setMaxWidthChars(30);

        var separatorLabel = infoLabel("\u2022", Classes.labelDim.add(Classes.caption));

        this.durationLabel = infoLabel("", Classes.labelDim.add(Classes.caption));
        this.durationLabel.setHalign(START);
        this.durationLabel.setSingleLineMode(true);

        subtitleBox.append(artistLabel);
        subtitleBox.append(separatorLabel);
        subtitleBox.append(durationLabel);

        contentBox.append(titleLabel);
        contentBox.append(subtitleBox);

        this.append(trackNumberLabel);
        this.append(contentBox);

        this.onDestroy(this::unbind);
    }

    public void bind(GQueueItem item, ListItem listItem) {
        this.gQueueItem = item;
        this.songInfo = item.songInfo();

        // Listen for IS_CURRENT changes
        var currentConnection = this.gQueueItem.onNotify(
                GQueueItem.Signal.IS_CURRENT.getId(),
                _ -> updateCurrentStyling()
        );
        var oldCurrentConnection = this.isCurrentSignal.getAndSet(currentConnection);
        if (oldCurrentConnection != null) {
            oldCurrentConnection.disconnect();
        }

        // Listen for position changes
        var positionConnection = listItem.onNotify("position", _ -> {
            int pos = listItem.getPosition();
            this.index.set(pos);
            int trackNumber = pos + 1;
            this.trackNumberLabel.setLabel("%d".formatted(trackNumber));
        });
        var oldPositionConnection = this.positionSignal.getAndSet(positionConnection);
        if (oldPositionConnection != null) {
            oldPositionConnection.disconnect();
        }

        this.index.set(listItem.getPosition());
        updateView();
        updateCurrentStyling();
    }

    public void unbind() {
        var sig1 = this.isCurrentSignal.getAndSet(null);
        if (sig1 != null) {
            sig1.disconnect();
        }
        var sig2 = this.positionSignal.getAndSet(null);
        if (sig2 != null) {
            sig2.disconnect();
        }
    }

    private void updateView() {
        if (this.songInfo == null) {
            return;
        }

        int trackNumber = this.index.get() + 1;
        this.trackNumberLabel.setLabel("%d".formatted(trackNumber));
        this.titleLabel.setLabel(songInfo.title());
        this.artistLabel.setLabel(songInfo.artist());
        this.durationLabel.setLabel(Utils.formatDurationShort(songInfo.duration()));
    }

    private void updateCurrentStyling() {
        if (this.gQueueItem == null) {
            return;
        }
        Utils.runOnMainThread(() -> {
            if (this.gQueueItem.isCurrent()) {
                this.titleLabel.addCssClass(Classes.colorAccent.className());
            } else {
                this.titleLabel.removeCssClass(Classes.colorAccent.className());
            }
        });
    }
}
