package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.models.GQueueItem;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class PlayQueueItemRow extends Box {
    private static final int ALBUM_ART_SIZE = 40;

    private final AppManager appManager;
    private GQueueItem gQueueItem;
    private GSongInfo gSongInfo;
    private SongInfo songInfo;

    private final RoundedAlbumArt albumArt;
    private final Label titleLabel;
    private final Label artistLabel;
    private final Label durationLabel;
    private final Button removeButton;

    private final AtomicReference<SignalConnection<?>> isCurrentSignal = new AtomicReference<>();
    private final AtomicReference<SignalConnection<?>> positionSignal = new AtomicReference<>();
    private final AtomicInteger index = new AtomicInteger(0);

    public PlayQueueItemRow(AppManager appManager) {
        super(HORIZONTAL, 8);
        this.appManager = appManager;

        this.setMarginTop(6);
        this.setMarginBottom(6);
        this.setMarginStart(8);
        this.setMarginEnd(8);

        // Album art widget
        this.albumArt = new RoundedAlbumArt(Optional.empty(), appManager, ALBUM_ART_SIZE);
        this.albumArt.setClickable(false);

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

        this.removeButton = Button.builder()
                .setIconName(Icons.ListRemove.getIconName())
                .build();
        this.removeButton.addCssClass(Classes.flat.className());
        this.removeButton.addCssClass("circular");
        this.removeButton.addCssClass(Classes.colorError.className());
        this.removeButton.setValign(CENTER);
        this.removeButton.onClicked(() -> {
            int pos = this.index.get();
            this.appManager.handleAction(new PlayerAction.RemoveFromQueue(pos));
        });

        this.append(albumArt);
        this.append(contentBox);
        this.append(removeButton);

        this.onDestroy(this::unbind);
    }

    public void bind(GQueueItem item, ListItem listItem) {
        this.gQueueItem = item;
        this.gSongInfo = item.getSongInfo();
        this.songInfo = item.getSongInfo().getSongInfo();

        // Listen for IS_CURRENT changes
        var currentConnection = this.gSongInfo.onNotify(
                GSongInfo.Signal.IS_PLAYING.getId(),
                _ -> updateStyling()
        );
        var oldCurrentConnection = this.isCurrentSignal.getAndSet(currentConnection);
        if (oldCurrentConnection != null) {
            oldCurrentConnection.disconnect();
        }

        // Listen for position changes
        var positionConnection = listItem.onNotify("position", _ -> {
            int pos = listItem.getPosition();
            this.index.set(pos);
        });
        var oldPositionConnection = this.positionSignal.getAndSet(positionConnection);
        if (oldPositionConnection != null) {
            oldPositionConnection.disconnect();
        }

        this.index.set(listItem.getPosition());
        updateView();
        updateStyling();
    }

    private void updateStyling() {
        updateCurrentStyling();
        updateQueueKindStyling();
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
        this.songInfo = null;
        this.gSongInfo = null;
        this.gQueueItem = null;
    }

    private void updateView() {
        if (this.gSongInfo == null) {
            return;
        }

        this.titleLabel.setLabel(songInfo.title());
        this.artistLabel.setLabel(songInfo.artist());
        this.durationLabel.setLabel(Utils.formatDurationShort(songInfo.duration()));

        // Update album art in-place
        this.albumArt.update(this.songInfo.coverArt());
    }

    private void updateQueueKindStyling() {
        if (this.gQueueItem == null) {
            return;
        }
        Utils.runOnMainThread(() -> {
            if (this.gSongInfo == null || this.gQueueItem == null) {
                return;
            }
            if (this.gSongInfo.getIsPlaying()) {
                this.removeCssClass(Classes.queueAutomatic.className());
                return;
            }
            if (this.gQueueItem.getIsUserQueued()) {
                this.removeCssClass(Classes.queueAutomatic.className());
            } else {
                this.addCssClass(Classes.queueAutomatic.className());
            }
        });
    }

    private void updateCurrentStyling() {
        if (this.gQueueItem == null || this.gSongInfo == null) {
            return;
        }
        Utils.runOnMainThread(() -> {
            if (this.gSongInfo == null) {
                return;
            }
            if (this.gSongInfo.getIsPlaying()) {
                this.titleLabel.addCssClass(Classes.colorAccent.className());
            } else {
                this.titleLabel.removeCssClass(Classes.colorAccent.className());
            }
        });
    }
}
