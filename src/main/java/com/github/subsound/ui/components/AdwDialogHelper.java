package com.github.subsound.ui.components;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.ResponseAppearance;
import org.gnome.gtk.Widget;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AdwDialogHelper extends AlertDialog {
    public AdwDialogHelper() {
        super();
    }

    public static final String CANCEL_LABEL_ID = "cancel";
    public record Response(String label, String text, ResponseAppearance appearance) {
        public Response(String label, String text) {
            this(label, text, ResponseAppearance.DEFAULT);
        }
    }
    public record DialogResult(String label) {}
    public static CompletableFuture<DialogResult> ofDialog(
            Widget parent,
            String title,
            String body,
            List<Response> choices
    ) {
        var h = new AdwDialogHelper();
        h.setTitle(title);
        h.setBody(body);

        // example of typical responses:
        //h.addResponse("cancel", "_Cancel");
        //h.addResponse("delete-confirmed", "_Delete");
        for (var r : choices) {
            h.addResponse(r.label, r.text);
            if (r.appearance != null) {
                h.setResponseAppearance(r.label, r.appearance);
            }
        }

        var closeResponse = choices.stream().filter(r -> CANCEL_LABEL_ID.equals(r.label)).findFirst().orElseThrow();
        h.setDefaultResponse(closeResponse.label);
        h.setCloseResponse(closeResponse.label);

        var f = new CompletableFuture<DialogResult>();
        h.onResponse("", response -> f.complete(new DialogResult(response)));
        h.present(parent);
        // choose(..) is the async callback api for the dialog
        //h.choose();
        return f;
    }
}
