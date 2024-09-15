package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient.ServerType;
import org.gnome.adw.Clamp;
import org.gnome.adw.EntryRow;
import org.gnome.adw.PasswordEntryRow;
import org.gnome.adw.SwitchRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.SelectionMode;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.borderBox;
import static org.gnome.gtk.Orientation.VERTICAL;

public class SettingsPage extends Box {

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

    public SettingsPage() {
        super(VERTICAL, 0);
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);

        this.serverTypeInfoLabel = Label.builder().setLabel("").setCssClasses(Classes.title1.add()).build();
        this.serverUrlEntry = EntryRow.builder().setTitle("Server URL").build();
        this.tlsSwitchEntry = SwitchRow.builder().setTitle("Accept unverified certificate").build();
        this.usernameEntry = EntryRow.builder().setTitle("Username").build();
        this.passwordEntry = PasswordEntryRow.builder().setTitle("Password").build();
        this.testButton = Button.builder().setLabel("Test").build();
        this.saveButton = Button.builder().setLabel("Save").build();

        this.listBox = ListBox.builder().setSelectionMode(SelectionMode.NONE).setCssClasses(Classes.boxedList.add()).build();
        this.listBox.append(serverTypeInfoLabel);
        this.listBox.append(serverUrlEntry);
        this.listBox.append(tlsSwitchEntry);
        this.listBox.append(usernameEntry);
        this.listBox.append(passwordEntry);
        this.listBox.append(testButton);
        this.listBox.append(saveButton);

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(this.listBox);
        this.clamp = Clamp.builder().setMaximumSize(600).setChild(this.centerBox).build();
        this.append(clamp);
    }

    public record SettingsInfo(
            ServerType type,
            String serverUrl,
            String username,
            String password
    ) {}

    public void setSettingsInfo(SettingsInfo s) {
        this.serverTypeInfoLabel.setLabel("%s server".formatted(s.type.name()));
        this.serverUrlEntry.setText(s.serverUrl);
        this.usernameEntry.setText(s.username);
        this.passwordEntry.setText(s.password);
    }
}
