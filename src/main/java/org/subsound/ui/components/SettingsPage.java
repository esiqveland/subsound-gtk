package org.subsound.ui.components;

import org.gnome.adw.ButtonRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.ComboRow;
import org.gnome.adw.PreferencesGroup;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.StringList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.TranscodeBitrate.MaximumBitrate;
import org.subsound.integration.ServerClient.TranscodeBitrate.SourceQuality;
import org.subsound.integration.ServerClient.TranscodeSettings;
import org.subsound.ui.components.ServerConfigForm.SettingsInfo;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.ui.components.Classes.destructiveAction;
import static org.subsound.utils.Utils.borderBox;

public class SettingsPage extends Box {
    private final Logger log = org.slf4j.LoggerFactory.getLogger(SettingsPage.class);

    private final ServerConfigForm form;
    private final ButtonRow clearSongCacheButton;
    private final ButtonRow clearThumbnailCacheButton;
    private final PreferencesGroup localSettings;
    private final PreferencesGroup transcodeSettings;
    private final ComboRow audioFormatCombo;
    private final ComboRow audioBitrateCombo;
    private final Box centerBox;

    public SettingsPage(
            @Nullable SettingsInfo settingsInfo,
            Path dataDir,
            @Nullable TranscodeSettings transcodeSettings,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);
        this.clearSongCacheButton = ButtonRow.builder().setTitle("Clear song cache").build();
        this.clearSongCacheButton.addCssClass(destructiveAction.className());
        this.clearSongCacheButton.onActivated(() -> onAction.apply(new PlayerAction.ClearSongCache()));

        this.clearThumbnailCacheButton = ButtonRow.builder().setTitle("Clear thumbnail cache").build();
        this.clearThumbnailCacheButton.addCssClass(destructiveAction.className());
        this.clearThumbnailCacheButton.onActivated(() -> onAction.apply(new PlayerAction.ClearThumbnailCache()));

        this.localSettings = new PreferencesGroup();
        this.localSettings.setTitle("Local settings");
        this.localSettings.setSeparateRows(false);
        this.localSettings.add(clearSongCacheButton);
        this.localSettings.add(clearThumbnailCacheButton);

        var formats = Arrays.stream(ServerClient.TranscodeFormat.values()).toList();
        var model = new StringList();
        formats.forEach(val -> model.append(val.name()));
        List<ServerClient.TranscodeBitrate> bitRates = List.of(
                new SourceQuality(),
                MaximumBitrate.of(64),
                MaximumBitrate.of(96),
                MaximumBitrate.of(128),
                MaximumBitrate.of(160),
                MaximumBitrate.of(192),
                MaximumBitrate.of(256),
                MaximumBitrate.of(320)
        );

        this.audioFormatCombo = new ComboRow();
        this.audioFormatCombo.setTitle("Audio format");
        this.audioFormatCombo.setModel(model);
        var initialFormat = transcodeSettings != null ? transcodeSettings.format() : ServerClient.TranscodeFormat.opus;
        this.audioFormatCombo.setSelected(formats.indexOf(initialFormat));

        var bitrateModelList = new StringList();
        bitRates.stream().map(value -> switch (value) {
            case SourceQuality _ -> "Source";
            case MaximumBitrate(var bitrate) -> String.format("%d", bitrate);
        }).forEach(bitrateModelList::append);
        this.audioBitrateCombo = new ComboRow();
        this.audioBitrateCombo.setTitle("Max bitrate");
        this.audioBitrateCombo.setModel(bitrateModelList);
        var initialBitrate = transcodeSettings != null ? transcodeSettings.bitrate() : new SourceQuality();
        initialBitrate = initialBitrate == null ? new SourceQuality() : initialBitrate;
        this.audioBitrateCombo.setSelected(Math.max(0, bitRates.indexOf(initialBitrate)));

        this.audioFormatCombo.onNotify(
                "selected", _ -> {
                    var fmt = formats.get(this.audioFormatCombo.getSelected());
                    var bitrate = bitRates.get(this.audioBitrateCombo.getSelected());
                    var selected = new TranscodeSettings(fmt, bitrate);
                    log.info("Selected transcode settings: {}", selected);
                    onAction.apply(new PlayerAction.SaveTranscodeFormat(selected));
                }
        );
        this.audioBitrateCombo.onNotify(
                "selected", _ -> {
                    var fmt = formats.get(this.audioFormatCombo.getSelected());
                    var bitrate = bitRates.get(this.audioBitrateCombo.getSelected());
                    var selected = new TranscodeSettings(fmt, bitrate);
                    log.info("Selected transcode settings: {}", selected);
                    onAction.apply(new PlayerAction.SaveTranscodeFormat(selected));
                }
        );

        this.transcodeSettings = new PreferencesGroup();
        this.transcodeSettings.setTitle("Transcode settings");
        this.transcodeSettings.setSeparateRows(false);
        this.transcodeSettings.add(audioFormatCombo);
        this.transcodeSettings.add(audioBitrateCombo);

        this.form = new ServerConfigForm(
                settingsInfo,
                dataDir,
                onAction
        );

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(this.form);
        this.centerBox.append(this.transcodeSettings);
        this.centerBox.append(this.localSettings);

        var clamp = new Clamp();
        clamp.setMaximumSize(600);
        clamp.setChild(this.centerBox);
        this.append(clamp);
    }

    public void setSettingsInfo(@Nullable SettingsInfo s) {
        this.form.setSettingsInfo(s);
    }
}
