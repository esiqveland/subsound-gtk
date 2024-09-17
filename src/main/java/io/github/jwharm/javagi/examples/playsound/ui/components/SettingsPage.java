package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.app.state.PlayerAction;
import io.github.jwharm.javagi.examples.playsound.configuration.Config.ServerConfig;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;
import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ServerType;
import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.adw.Clamp;
import org.gnome.adw.EntryRow;
import org.gnome.adw.PasswordEntryRow;
import org.gnome.adw.SwitchRow;
import org.gnome.adw.Toast;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.SelectionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.boxedList;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.colorError;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.colorSuccess;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.colorWarning;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.none;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.suggestedAction;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.title1;
import static io.github.jwharm.javagi.examples.playsound.ui.components.Classes.titleLarge;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.borderBox;
import static io.github.jwharm.javagi.examples.playsound.utils.javahttp.TextUtils.capitalize;
import static org.gnome.gtk.Orientation.VERTICAL;

public class SettingsPage extends Box {
    private static final Logger log = LoggerFactory.getLogger(SettingsPage.class);

    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Clamp clamp;
    private final Box centerBox;

    private final ListBox listBox;
    private final Label serverTypeInfoLabel;
    private final EntryRow serverUrlEntry;
    private final SwitchRow tlsSwitchEntry;
    private final EntryRow usernameEntry;
    private final PasswordEntryRow passwordEntry;
    private final Button testButton;
    private final Button saveButton;

    public SettingsPage(
            SettingsInfo settingsInfo,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.onAction = onAction;
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);

        this.serverTypeInfoLabel = Label.builder().setLabel("").setCssClasses(titleLarge.add()).build();
        this.serverUrlEntry = EntryRow.builder().setTitle("Server URL").build();
        this.tlsSwitchEntry = SwitchRow.builder().setTitle("Accept unverified certificate").setSensitive(false).build();
        this.usernameEntry = EntryRow.builder().setTitle("Username").build();
        this.passwordEntry = PasswordEntryRow.builder().setTitle("Password").build();
        this.testButton = Button.builder().setLabel("Test").setCssClasses(none.add()).build();
        this.testButton.onClicked(() -> this.testConnection());
        this.saveButton = Button.builder().setLabel("Save").setCssClasses(title1.add(suggestedAction)).setSensitive(false).build();
        this.saveButton.onClicked(() -> this.saveForm());

        this.listBox = ListBox.builder().setSelectionMode(SelectionMode.NONE).setCssClasses(boxedList.add()).build();
        this.listBox.append(serverUrlEntry);
        this.listBox.append(tlsSwitchEntry);
        this.listBox.append(usernameEntry);
        this.listBox.append(passwordEntry);
        this.listBox.append(testButton);
        this.listBox.append(saveButton);

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(serverTypeInfoLabel);
        this.centerBox.append(this.listBox);
        this.clamp = Clamp.builder().setMaximumSize(600).setChild(this.centerBox).build();
        this.append(clamp);
        this.setSettingsInfo(settingsInfo);
    }

    private void testConnection() {
        this.testButton.setCssClasses(none.add());
        this.saveButton.setSensitive(false);
        var data = getFormData();
        Utils.doAsync(() -> {
            ServerClient serverClient = ServerClient.create(new ServerConfig(
                    data.type,
                    data.serverUrl,
                    data.username,
                    data.password
            ));
            boolean success = serverClient.testConnection();
            if (success) {
                Toast build = Toast.builder()
                        .setTimeout(1)
                        .setCustomTitle(Label.builder().setLabel("Connection OK!").setCssClasses(title1.add(colorSuccess)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            } else {
                Toast build = Toast.builder()
                        .setTimeout(2)
                        .setCustomTitle(Label.builder().setLabel("Connection failed!").setCssClasses(title1.add(colorWarning)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            }
            Utils.runOnMainThread(() -> {
                this.saveButton.setSensitive(success);
                Classes color = success ? colorSuccess : colorError;
                this.testButton.setCssClasses(color.add());
            });
        });
    }

    public record SettingsInfo(
            ServerType type,
            String serverUrl,
            String username,
            String password
    ) {
    }

    public void setSettingsInfo(SettingsInfo s) {
        Utils.runOnMainThread(() -> {
            this.serverTypeInfoLabel.setLabel("%s server".formatted(capitalize(s.type.name())));
            this.serverUrlEntry.setText(s.serverUrl);
            this.usernameEntry.setText(s.username);
            this.passwordEntry.setText(s.password);
            this.tlsSwitchEntry.setSensitive(false);
            this.testButton.setSensitive(true);
            this.testButton.setCssClasses(none.add());
            this.saveButton.setSensitive(false);
        });
    }

    private void saveForm() {
        var data = getFormData();
        log.info("saveForm: data={}", data);
        onAction.apply(new PlayerAction.SaveConfig(data))
                .handle((success, throwable) -> {
                    if (throwable != null) {
                        log.error("saveForm: data={} error: ", data, throwable);
                        Toast toast = Toast.builder()
                                .setTimeout(30)
                                .setCustomTitle(Label.builder().setLabel("Error saving configuration!").setCssClasses(title1.add(colorError)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    } else {
                        log.info("saveForm: data={} success!", data);
                        Toast toast = Toast.builder()
                                .setTimeout(4)
                                .setCustomTitle(Label.builder().setLabel("Configuration saved!").setCssClasses(title1.add(colorSuccess)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    }
                })
                .thenAccept(this.onAction::apply);
    }


    private SettingsInfo getFormData() {
        return new SettingsInfo(
                ServerType.SUBSONIC,
                this.serverUrlEntry.getText(),
                this.usernameEntry.getText(),
                this.passwordEntry.getText()
        );
    }
}
