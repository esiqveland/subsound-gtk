package com.github.subsound.ui.components;

import com.github.subsound.app.state.PlayQueue.PlayQueueState;
import com.github.subsound.integration.ServerClient.SongInfo;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SelectionMode;
import org.gnome.pango.EllipsizeMode;

import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public class PlayQueuePopover extends Popover {
    private final ListBox queueListBox;
    private final ScrolledWindow queueScrolled;
    private final Supplier<PlayQueueState> queueStateSupplier;

    public PlayQueuePopover(Supplier<PlayQueueState> queueStateSupplier, IntConsumer onPlayPosition) {
        super();
        this.queueStateSupplier = queueStateSupplier;

        queueListBox = ListBox.builder()
                .setSelectionMode(SelectionMode.SINGLE)
                .build();
        queueListBox.addCssClass("boxed-list");
        queueListBox.onRowActivated(row -> {
            int index = row.getIndex();
            this.popdown();
            onPlayPosition.accept(index);
        });

        queueScrolled = ScrolledWindow.builder()
                .setChild(queueListBox)
                .setMinContentHeight(200)
                .setMaxContentHeight(800)
                .setMinContentWidth(300)
                .build();

        var queueHeader = Label.builder()
                .setLabel("Play Queue")
                .setMarginTop(8)
                .setMarginBottom(8)
                .build();
        queueHeader.addCssClass("heading");

        var queuePopoverContent = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(4)
                .build();
        queuePopoverContent.append(queueHeader);
        queuePopoverContent.append(queueScrolled);

        this.setChild(queuePopoverContent);
        this.setPosition(PositionType.TOP);
        this.onShow(() -> {
            this.updateQueueList();
        });
    }

    private void updateQueueList() {
        // Clear existing items
        var child = queueListBox.getFirstChild();
        while (child != null) {
            var next = child.getNextSibling();
            queueListBox.remove(child);
            child = next;
        }

        var queueState = queueStateSupplier.get();
        List<SongInfo> queue = queueState.playQueue();
        Optional<Integer> currentPosition = queueState.position();

        if (queue.isEmpty()) {
            var emptyLabel = Label.builder()
                    .setLabel("Queue is empty")
                    .setMarginTop(16)
                    .setMarginBottom(16)
                    .setMarginStart(16)
                    .setMarginEnd(16)
                    .build();
            emptyLabel.addCssClass("dim-label");
            queueListBox.append(emptyLabel);
            return;
        }

        for (int i = 0; i < queue.size(); i++) {
            var song = queue.get(i);
            final int index = i;
            boolean isCurrent = currentPosition.map(pos -> pos == index).orElse(false);

            var titleLabel = Label.builder()
                    .setLabel(song.title())
                    .setHalign(Align.START)
                    .setEllipsize(EllipsizeMode.END)
                    .setMaxWidthChars(35)
                    .build();

            var artistLabel = Label.builder()
                    .setLabel(song.artist())
                    .setHalign(Align.START)
                    .setEllipsize(EllipsizeMode.END)
                    .setMaxWidthChars(35)
                    .build();
            artistLabel.addCssClass("dim-label");
            artistLabel.addCssClass("caption");

            var rowBox = Box.builder()
                    .setOrientation(Orientation.VERTICAL)
                    .setSpacing(2)
                    .setMarginTop(6)
                    .setMarginBottom(6)
                    .setMarginStart(12)
                    .setMarginEnd(12)
                    .build();
            rowBox.append(titleLabel);
            rowBox.append(artistLabel);

            if (isCurrent) {
                titleLabel.addCssClass("accent");
            }

            queueListBox.append(rowBox);
        }

        // Scroll to the currently playing song
        currentPosition.ifPresent(position -> {
            var row = queueListBox.getRowAtIndex(position);
            if (row != null) {
                // Select the row to scroll it into view
                queueListBox.selectRow(row);
            }
        });
    }
}