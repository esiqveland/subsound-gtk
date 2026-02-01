package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.ui.models.GQueueItem;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListScrollFlags;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.Window;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

public class PlayQueuePopover extends Popover {
    private final ListView queueListView;
    private final ScrolledWindow queueScrolled;
    private final ListStore<GQueueItem> listModel;
    private final SingleSelection<GQueueItem> selectionModel;
    private final SignalListItemFactory factory;
    private final Box emptyStateBox;

    public PlayQueuePopover(AppManager appManager, IntConsumer onPlayPosition) {
        super();
        this.listModel = appManager.getQueueListStore();

        factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);
            var row = new PlayQueueItemRow(appManager);
            listitem.setChild(row);
        });

        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            var item = (GQueueItem) listitem.getItem();
            if (item == null) {
                return;
            }
            var child = listitem.getChild();
            if (child instanceof PlayQueueItemRow row) {
                row.bind(item, listitem);
            }
        });

        factory.onUnbind(object -> {
            ListItem listitem = (ListItem) object;
            var child = listitem.getChild();
            if (child instanceof PlayQueueItemRow row) {
                row.unbind();
            }
        });

        factory.onTeardown(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setChild(null);
        });

        this.selectionModel = new SingleSelection<>(this.listModel);
        this.selectionModel.setAutoselect(false);
        this.selectionModel.setCanUnselect(true);

        this.queueListView = ListView.builder()
                .setShowSeparators(true)
                .setOrientation(Orientation.VERTICAL)
                .setSingleClickActivate(true)
                .setFactory(factory)
                .setModel(selectionModel)
                .build();
        this.queueListView.addCssClass("boxed-list");

        this.queueListView.onActivate(index -> {
            this.popdown();
            onPlayPosition.accept(index);
        });

        queueScrolled = ScrolledWindow.builder()
                .setChild(queueListView)
                .setMinContentHeight(200)
                .setMaxContentHeight(800)
                .setPropagateNaturalHeight(true)
                .setMinContentWidth(340)
                .build();

        // Empty state
        emptyStateBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setMarginTop(16)
                .setMarginBottom(16)
                .setMarginStart(16)
                .setMarginEnd(16)
                .build();
        var emptyLabel = Label.builder()
                .setLabel("Queue is empty")
                .build();
        emptyLabel.addCssClass("dim-label");
        emptyStateBox.append(emptyLabel);

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
        queuePopoverContent.append(emptyStateBox);

        this.setChild(queuePopoverContent);
        this.setPosition(PositionType.TOP);

        this.onShow(() -> {
            updateMaxHeight();
            updateEmptyState();
            scrollToCurrentItem();
        });
    }

    private void updateMaxHeight() {
        var root = this.getRoot();
        if (root instanceof Window window) {
            int windowHeight = window.getHeight();
            if (windowHeight > 0) {
                int maxHeight = Math.max(400, windowHeight - 150);
                queueScrolled.setMaxContentHeight(maxHeight);
            }
        }
    }

    private void updateEmptyState() {
        boolean isEmpty = listModel.getNItems() == 0;
        queueScrolled.setVisible(!isEmpty);
        emptyStateBox.setVisible(isEmpty);
    }

    private void scrollToCurrentItem() {
        for (int i = 0; i < listModel.getNItems(); i++) {
            var item = listModel.getItem(i);
            if (item != null && item.getIsCurrent()) {
                this.queueListView.scrollTo(i, ListScrollFlags.FOCUS, null);
                break;
            }
        }
    }
}
