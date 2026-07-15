package org.subsound.ui.components;

import org.gnome.adw.ButtonRow;
import org.gnome.adw.EntryRow;
import org.gnome.adw.PasswordEntryRow;
import org.gnome.adw.SwitchRow;
import org.gnome.adw.Toast;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Entry;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.SelectionMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.HttpHeader;
import org.subsound.integration.ServerClient.ServerType;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.utils.Utils;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.i18n.I18n.tr;
import static org.subsound.ui.components.Classes.boxedList;
import static org.subsound.ui.components.Classes.colorError;
import static org.subsound.ui.components.Classes.colorSuccess;
import static org.subsound.ui.components.Classes.colorWarning;
import static org.subsound.ui.components.Classes.suggestedAction;
import static org.subsound.ui.components.Classes.title1;
import static org.subsound.ui.components.Classes.titleLarge;
import static org.subsound.utils.Utils.borderBox;
import static org.subsound.utils.javahttp.TextUtils.capitalize;

public class ServerConfigForm extends Box {
    private static final Logger log = LoggerFactory.getLogger(ServerConfigForm.class);

    private final Path dataDir;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Box centerBox;

    final ListBox listBox;
    private final Label serverTypeInfoLabel;
    private final EntryRow serverUrlEntry;
    private final SwitchRow tlsSwitchEntry;
    private final EntryRow usernameEntry;
    private final PasswordEntryRow passwordEntry;
    private final ListBox headersListBox;
    private final ButtonRow addHeaderButton;
    private final List<HeaderRow> headerRows = new ArrayList<>();
    private final ButtonRow testButton;
    private final ButtonRow saveButton;

    public record SettingsInfo(
            ServerType type,
            String serverUrl,
            String username,
            String password,
            boolean tlsSkipVerify,
            List<HttpHeader> customHeaders
    ) {
        @Override
        public @NonNull String toString() {
            return "SettingsInfo[type=%s, serverUrl=%s, username=%s, password=[REDACTED], tlsSkipVerify=%s, customHeaders=%d]".formatted(
                    type,
                    serverUrl,
                    username,
                    tlsSkipVerify,
                    customHeaders != null ? customHeaders.size() : 0
            );
        }
    }

    /** A single editable name/value header row appended to {@link #headersListBox}. */
    private final class HeaderRow {
        final ListBoxRow row;
        final Entry nameEntry;
        final Entry valueEntry;

        HeaderRow(String name, String value) {
            this.nameEntry = Entry.builder()
                    .setPlaceholderText(tr("Header name"))
                    .setText(name != null ? name : "")
                    .setHexpand(true)
                    .build();
            this.valueEntry = Entry.builder()
                    .setPlaceholderText(tr("Value"))
                    .setText(value != null ? value : "")
                    .setHexpand(true)
                    .build();
            var removeButton = Button.builder()
                    .setIconName("list-remove-symbolic")
                    .setValign(Align.CENTER)
                    .setCssClasses(new String[]{Classes.flat.className(), Classes.destructiveAction.className()})
                    .build();
            removeButton.onClicked(() -> removeHeaderRow(this));
            var box = Box.builder().setOrientation(HORIZONTAL).setSpacing(6).build();
            box.setMarginTop(6);
            box.setMarginBottom(6);
            box.setMarginStart(6);
            box.setMarginEnd(6);
            box.append(this.nameEntry);
            box.append(this.valueEntry);
            box.append(removeButton);
            // Wrap in an explicit, non-activatable ListBoxRow so removal targets a
            // direct child of the ListBox (removing the auto-wrapped box is unreliable).
            this.row = ListBoxRow.builder()
                    .setChild(box)
                    .setActivatable(false)
                    .setSelectable(false)
                    .build();
        }
    }

    public ServerConfigForm(
            @Nullable SettingsInfo settingsInfo,
            Path dataDir,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.dataDir = dataDir;
        this.onAction = onAction;
        this.setValign(Align.CENTER);
        this.setHalign(Align.FILL);
        this.setHexpand(true);

        this.serverTypeInfoLabel = Label.builder().setLabel(tr("New Server")).setCssClasses(titleLarge.add()).build();
        this.serverUrlEntry = EntryRow.builder().setTitle(tr("Server URL")).setText("https://").build();
        this.tlsSwitchEntry = SwitchRow.builder().setTitle(tr("Skip TLS verification")).setSubtitle(tr("Accept self-signed certificates")).build();
        this.usernameEntry = EntryRow.builder().setTitle(tr("Username")).build();
        this.passwordEntry = PasswordEntryRow.builder().setTitle(tr("Password")).build();

        this.testButton = ButtonRow.builder().setTitle(tr("Test")).build();
        this.testButton.onActivated(() -> this.testConnection());
        this.saveButton = ButtonRow.builder().setTitle(tr("Save")).setSensitive(false).build();
        this.saveButton.onActivated(() -> this.saveForm());
        this.saveButton.addCssClass(suggestedAction.className());

        this.listBox = new ListBox();
        this.listBox.setHexpand(true);
        this.listBox.addCssClass(boxedList.className());
        this.listBox.setSelectionMode(SelectionMode.NONE);

        this.listBox.append(serverUrlEntry);
        this.listBox.append(tlsSwitchEntry);
        this.listBox.append(usernameEntry);
        this.listBox.append(passwordEntry);

        // Custom headers section: a dynamic list of name/value rows plus an "+ Add more" row.
        var headersLabel = Label.builder()
                .setLabel(tr("Custom headers"))
                .setHalign(Align.START)
                .setCssClasses(title1.add())
                .build();
        this.headersListBox = new ListBox();
        this.headersListBox.setHexpand(true);
        this.headersListBox.addCssClass(boxedList.className());
        this.headersListBox.setSelectionMode(SelectionMode.NONE);
        this.addHeaderButton = ButtonRow.builder().setTitle(tr("+ Add custom header")).build();
        this.addHeaderButton.onActivated(() -> this.addHeaderRow("", ""));
        this.headersListBox.append(this.addHeaderButton);

        var actionsListBox = new ListBox();
        actionsListBox.setHexpand(true);
        actionsListBox.addCssClass(boxedList.className());
        actionsListBox.setSelectionMode(SelectionMode.NONE);
        actionsListBox.append(testButton);
        actionsListBox.append(saveButton);

        this.centerBox = borderBox(VERTICAL, 0).setSpacing(8).build();
        this.centerBox.append(serverTypeInfoLabel);
        this.centerBox.append(this.listBox);
        this.centerBox.append(headersLabel);
        this.centerBox.append(this.headersListBox);
        this.centerBox.append(actionsListBox);
        this.append(this.centerBox);
        this.setSettingsInfo(settingsInfo);
    }

    private void addHeaderRow(String name, String value) {
        var row = new HeaderRow(name, value);
        // Insert before the trailing "+ Add custom header" button (kept last).
        this.headersListBox.insert(row.row, this.headerRows.size());
        this.headerRows.add(row);
    }

    private void removeHeaderRow(HeaderRow row) {
        this.headersListBox.remove(row.row);
        this.headerRows.remove(row);
    }

    private void clearHeaderRows() {
        for (var row : this.headerRows) {
            this.headersListBox.remove(row.row);
        }
        this.headerRows.clear();
    }

    private List<HttpHeader> collectHeaders() {
        var headers = new ArrayList<HttpHeader>();
        for (var row : this.headerRows) {
            var name = row.nameEntry.getText().trim();
            var value = row.valueEntry.getText().trim();
            if (!name.isBlank()) {
                headers.add(new HttpHeader(name, value));
            }
        }
        return headers;
    }

    private void testConnection() {
        this.saveButton.setSensitive(false);
        var dataOpt = getFormData();
        dataOpt.ifPresent(data -> {
            log.info("testConnection: url={} username={} passwordLength={} tlsSkipVerify={}",
                    data.serverUrl, data.username, data.password != null ? data.password.length() : -1, data.tlsSkipVerify);
            Utils.doAsync(() -> {
            ServerClient serverClient = ServerClient.create(new ServerConfig(
                    this.dataDir,
                    AppManager.SERVER_ID,
                    data.type,
                    data.serverUrl,
                    data.username,
                    data.password,
                    TranscodeFormat.source,
                    null,
                    data.tlsSkipVerify,
                    data.customHeaders
            ));
            boolean success = serverClient.testConnection();
            if (success) {
                Toast build = Toast.builder()
                        .setTimeout(1)
                        .setCustomTitle(Label.builder().setLabel(tr("Connection OK!")).setCssClasses(title1.add(colorSuccess)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            } else {
                Toast build = Toast.builder()
                        .setTimeout(2)
                        .setCustomTitle(Label.builder().setLabel(tr("Connection failed!")).setCssClasses(title1.add(colorWarning)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            }
            Utils.runOnMainThread(() -> {
                this.saveButton.setSensitive(success);
                Classes color = success ? colorSuccess : colorError;
                this.testButton.addCssClass(color.className());
            });
        });
        });
    }

    public void setSettingsInfo(@Nullable SettingsInfo s) {
        if (s == null) {
            return;
        }
        Utils.runOnMainThread(() -> {
            this.serverTypeInfoLabel.setLabel(tr("%s server").formatted(capitalize(s.type.name())));
            this.serverUrlEntry.setText(s.serverUrl);
            this.tlsSwitchEntry.setActive(s.tlsSkipVerify);
            this.usernameEntry.setText(s.username);
            this.passwordEntry.setText(s.password);
            this.clearHeaderRows();
            if (s.customHeaders != null) {
                for (var header : s.customHeaders) {
                    this.addHeaderRow(header.name(), header.value());
                }
            }
            this.testButton.setSensitive(true);
            this.saveButton.setSensitive(false);
        });
    }

    private void saveForm() {
        var dataOpt = getFormData();
        log.info("saveForm: data={}", dataOpt);
        if (dataOpt.isEmpty()) {
            Toast toast = Toast.builder()
                    .setTimeout(5)
                    .setCustomTitle(Label.builder().setLabel(tr("Error saving configuration!")).setCssClasses(title1.add(colorError)).build())
                    .build();
            this.onAction.apply(new PlayerAction.Toast(toast));
            return;
        }
        var data = dataOpt.get();
        onAction.apply(new PlayerAction.SaveConfig(data))
                .handle((success, throwable) -> {
                    if (throwable != null) {
                        log.error("saveForm: data={} error: ", data, throwable);
                        Toast toast = Toast.builder()
                                .setTimeout(5)
                                .setCustomTitle(Label.builder().setLabel(tr("Error saving configuration!")).setCssClasses(title1.add(colorError)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    } else {
                        log.info("saveForm: data={} success!", data);
                        Toast toast = Toast.builder()
                                .setTimeout(4)
                                .setCustomTitle(Label.builder().setLabel(tr("Configuration saved!")).setCssClasses(title1.add(colorSuccess)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    }
                })
                .thenAccept(this.onAction::apply);
    }

    private Optional<SettingsInfo> getFormData() {
        var url = this.serverUrlEntry.getText();
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        try {
            var parsed = URI.create(url);
            this.serverUrlEntry.setText(url);

            return Optional.of(new SettingsInfo(
                    ServerType.SUBSONIC,
                    parsed.toString(),
                    this.usernameEntry.getText(),
                    this.passwordEntry.getText(),
                    this.tlsSwitchEntry.getActive(),
                    collectHeaders()
            ));
        } catch (IllegalArgumentException e) {
            this.serverUrlEntry.setText(url);
            return Optional.empty();
        }
    }
}
