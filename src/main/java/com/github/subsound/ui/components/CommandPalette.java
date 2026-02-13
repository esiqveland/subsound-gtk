package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.PlayerAction;
import com.github.subsound.app.state.SearchResultStore;
import com.github.subsound.integration.ServerClient.SongInfo;
import com.github.subsound.ui.models.GSongInfo;
import com.github.subsound.utils.Utils;
import org.gnome.gdk.ModifierType;
import org.gnome.glib.GLib;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListView;
import org.gnome.gtk.NoSelection;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;

import static com.github.subsound.ui.views.AlbumInfoPage.infoLabel;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class CommandPalette extends Overlay {
    private final AppManager appManager;
    private final Box backdrop;
    private final Box paletteCard;
    private final SearchEntry searchEntry;
    private final ListView resultsList;
    private final SearchResultStore searchStore;
    private boolean shown = false;
    private int searchGeneration = 0;

    public CommandPalette(
            AppManager appManager,
            Widget child
    ) {
        super();
        this.appManager = appManager;
        this.searchStore = this.appManager.getSearchResultStore();
        this.setChild(child);

        // Backdrop: semi-transparent overlay that fills the entire area
        this.backdrop = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .build();
        this.backdrop.addCssClass(Classes.commandPaletteBackdrop.className());

        // Click on backdrop to dismiss (CAPTURE phase so it fires before children)
        var clickGesture = new GestureClick();
        clickGesture.setPropagationPhase(PropagationPhase.CAPTURE);
        clickGesture.onReleased((nPress, x, y) -> {
            var picked = backdrop.pick(x, y, org.gnome.gtk.PickFlags.DEFAULT);
            if (picked == null || picked.equals(backdrop)) {
                hide();
            }
        });
        this.backdrop.addController(clickGesture);

        // Palette card: centered box at top
        this.paletteCard = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setHalign(Align.CENTER)
                .setValign(Align.START)
                .setSpacing(0)
                .build();
        this.paletteCard.addCssClass(Classes.commandPalette.className());
        this.paletteCard.addCssClass(Classes.background.className());
        this.paletteCard.addCssClass(Classes.shadow.className());

        // Search entry
        this.searchEntry = SearchEntry.builder()
                .setHexpand(true)
                .setPlaceholderText("Search...")
                .build();
        // Results list backed by the store
        var selectionModel = new NoSelection<>(searchStore.getStore());
        var factory = new SignalListItemFactory();
        factory.onSetup(item -> {
            var listItem = (ListItem) item;
            var row = new SearchResultRow();
            listItem.setChild(row);
        });
        factory.onBind(item -> {
            var listItem = (ListItem) item;
            var row = (SearchResultRow) listItem.getChild();
            var gSongInfo = (GSongInfo) listItem.getItem();
            if (row != null && gSongInfo != null) {
                row.bind(gSongInfo, appManager);
            }
        });
        factory.onUnbind(item -> {
            var listItem = (ListItem) item;
            var row = (SearchResultRow) listItem.getChild();
            if (row != null) {
                row.unbind();
            }
        });
        factory.onTeardown(item -> {
            var listItem = (ListItem) item;
            listItem.setChild(null);
        });

        this.resultsList = ListView.builder()
                .setModel(selectionModel)
                .setFactory(factory)
                .setHexpand(true)
                .setSingleClickActivate(true)
                .build();
        this.resultsList.setVisible(false);

        this.resultsList.onActivate(index -> {
            var gSongInfo = (GSongInfo) searchStore.getStore().getItem(index);
            if (gSongInfo == null) return;
            appManager.handleAction(new PlayerAction.PlaySong(gSongInfo.getSongInfo()));
            hide();
        });

        // Connect search entry to search store (debounced via generation counter)
        this.searchEntry.onSearchChanged(() -> {
            var generation = ++searchGeneration;
            var query = searchEntry.getText();
            if (query == null || query.isBlank()) {
                searchStore.clear();
                resultsList.setVisible(false);
                return;
            }
            GLib.timeoutAdd(GLib.PRIORITY_DEFAULT, 300, () -> {
                if (generation != searchGeneration) {
                    return GLib.SOURCE_REMOVE;
                }
                searchStore.searchAsync(query).thenAccept(result -> {
                    Utils.runOnMainThread(() -> {
                        resultsList.setVisible(searchStore.getStore().getNItems() > 0);
                    });
                });
                return GLib.SOURCE_REMOVE;
            });
        });

        var scrolledWindow = ScrolledWindow.builder()
                .setChild(resultsList)
                .setHscrollbarPolicy(PolicyType.NEVER)
                .setVscrollbarPolicy(PolicyType.AUTOMATIC)
                .setMaxContentHeight(400)
                .setPropagateNaturalHeight(true)
                .build();

        this.paletteCard.append(this.searchEntry);
        this.paletteCard.append(scrolledWindow);

        this.backdrop.append(this.paletteCard);

        // Key controller on the Overlay (CAPTURE phase fires before focused SearchEntry)
        var keyController = new EventControllerKey();
        keyController.setPropagationPhase(PropagationPhase.CAPTURE);
        keyController.onKeyPressed((keyval, keycode, state) -> {
            if (!shown) return false;
            // GDK_KEY_Escape = 0xFF1B
            if (keyval == 0xFF1B) {
                hide();
                return true;
            }
            // Ctrl+K = 0x6B with CONTROL_MASK
            if (keyval == 0x6B && state.contains(ModifierType.CONTROL_MASK)) {
                hide();
                return true;
            }
            return false;
        });
        this.addController(keyController);
    }

    public void startSearch(String query) {
        this.searchStore.searchAsync(query);
    }

    public void show() {
        if (shown) return;
        shown = true;
        this.addOverlay(backdrop);
        searchEntry.setText("");
        searchEntry.grabFocus();
    }

    public void hide() {
        if (!shown) return;
        shown = false;
        searchGeneration++;
        this.removeOverlay(backdrop);
        searchEntry.setText("");
        searchStore.clear();
        resultsList.setVisible(false);
    }

    public void toggle() {
        if (shown) {
            hide();
        } else {
            show();
        }
    }

    public boolean isShown() {
        return shown;
    }

    private static class SearchResultRow extends Box {
        private static final int ALBUM_ART_SIZE = 40;

        private final Box albumArtBox;
        private final Label titleLabel;
        private final Label artistLabel;
        private final Label durationLabel;

        SearchResultRow() {
            super(HORIZONTAL, 8);
            this.setMarginTop(6);
            this.setMarginBottom(6);
            this.setMarginStart(8);
            this.setMarginEnd(8);

            this.albumArtBox = new Box(HORIZONTAL, 0);
            this.albumArtBox.setHalign(CENTER);
            this.albumArtBox.setValign(CENTER);
            this.albumArtBox.setSizeRequest(ALBUM_ART_SIZE, ALBUM_ART_SIZE);

            var contentBox = new Box(VERTICAL, 2);
            contentBox.setHalign(START);
            contentBox.setValign(CENTER);
            contentBox.setVexpand(true);
            contentBox.setHexpand(true);

            this.titleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(Align.START)
                    .setEllipsize(EllipsizeMode.END)
                    .setMaxWidthChars(40)
                    .build();
            this.titleLabel.setSingleLineMode(true);

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

            this.append(albumArtBox);
            this.append(contentBox);
        }

        void bind(GSongInfo gSongInfo, AppManager appManager) {
            SongInfo songInfo = gSongInfo.getSongInfo();
            this.titleLabel.setLabel(songInfo.title());
            this.artistLabel.setLabel(songInfo.artist());
            this.durationLabel.setLabel(Utils.formatDurationShort(songInfo.duration()));

            // Clear old album art
            Widget child = this.albumArtBox.getFirstChild();
            while (child != null) {
                Widget next = child.getNextSibling();
                this.albumArtBox.remove(child);
                child = next;
            }
            var albumArt = RoundedAlbumArt.resolveCoverArt(
                    appManager,
                    songInfo.coverArt(),
                    ALBUM_ART_SIZE,
                    false
            );
            this.albumArtBox.append(albumArt);
        }

        void unbind() {
            this.titleLabel.setLabel("");
            this.artistLabel.setLabel("");
            this.durationLabel.setLabel("");
            Widget child = this.albumArtBox.getFirstChild();
            while (child != null) {
                Widget next = child.getNextSibling();
                this.albumArtBox.remove(child);
                child = next;
            }
        }
    }
}
