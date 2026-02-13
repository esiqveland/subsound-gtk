package com.github.subsound.ui.components;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.app.state.SearchResultStore;
import com.github.subsound.ui.models.GSongInfo;
import org.gnome.gdk.ModifierType;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.ListView;
import org.gnome.gtk.NoSelection;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.Widget;

public class CommandPalette extends Overlay {
    private final AppManager appManager;
    private final Box backdrop;
    private final Box paletteCard;
    private final SearchEntry searchEntry;
    private final ListView resultsList;
    private final SearchResultStore resultStore;
    private boolean shown = false;

    public CommandPalette(
            AppManager appManager,
            Widget child
    ) {
        super();
        this.appManager = appManager;
        this.resultStore = this.appManager.getSearchResultStore();
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
        var selectionModel = new NoSelection<>(resultStore.getStore());
        var factory = new SignalListItemFactory();
        this.resultsList = ListView.builder()
                .setModel(selectionModel)
                .setFactory(factory)
                .setHexpand(true)
                .build();
        this.resultsList.setVisible(false);

        this.paletteCard.append(this.searchEntry);
        this.paletteCard.append(this.resultsList);

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
        this.removeOverlay(backdrop);
        searchEntry.setText("");
        resultStore.clear();
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

    public SearchResultStore getResultStore() {
        return resultStore;
    }
}
