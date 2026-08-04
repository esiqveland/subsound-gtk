package org.subsound.ui.components;

import org.gnome.adw.ActionRow;
import org.gnome.adw.ButtonRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.ComboRow;
import org.gnome.adw.PreferencesGroup;
import org.gnome.adw.SpinRow;
import org.gnome.adw.SwitchRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.StringList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.NetworkMonitoring.NetworkStatus;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ReplayGainConfig;
import org.subsound.integration.ServerClient.TranscodeBitrate.MaximumBitrate;
import org.subsound.integration.ServerClient.TranscodeBitrate.SourceQuality;
import org.subsound.integration.ServerClient.TranscodeSettings;
import org.subsound.ui.components.ServerConfigForm.SettingsInfo;
import org.subsound.utils.Utils;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.i18n.I18n.tr;
import static org.subsound.ui.components.Classes.destructiveAction;
import static org.subsound.utils.Utils.borderBox;

public class SettingsPage extends Box {
    private final Logger log = org.slf4j.LoggerFactory.getLogger(SettingsPage.class);

    private final AppManager appManager;
    private final ServerConfigForm form;
    private final ButtonRow clearSongCacheButton;
    private final ButtonRow clearThumbnailCacheButton;
    private final ButtonRow clearLyricsCacheButton;
    private final PreferencesGroup serverInformation;
    private final ActionRow serverTypeLabel;
    private final ActionRow serverVersionLabel;
    private final ActionRow apiVersionLabel;
    private final ActionRow serverOpenSubsonic;
    private final PreferencesGroup serverOpenSubsonicGroup = new PreferencesGroup();
    private List<ActionRow> serverOpenSubsonicFeatureList = List.of();
    private final PreferencesGroup localSettings;
    private final PreferencesGroup transcodeSettings;
    private final ComboRow audioFormatCombo;
    private final ComboRow audioBitrateCombo;
    private final PreferencesGroup replayGainSettings;
    private final SwitchRow rgEnabledSwitch;
    private final ComboRow rgModeCombo;
    private final SpinRow rgPreAmpSpin;
    private final SpinRow rgFallbackSpin;
    private final PreferencesGroup serverLibrary;
    private final ButtonRow librarySyncRow;
    private final ButtonRow libraryQuickScanRow;
    private final ActionRow libraryLastScanAtRow;
    private final ActionRow librarySongsRow;
    private final ActionRow libraryScanStatus;
    private final PreferencesGroup syncGroup;
    private final ActionRow localSongsRow;
    private final ButtonRow localSongsTriggerSync;
    private final AppManager.StateListener networkStateListener;
    private final Box centerBox;

    public SettingsPage(
            AppManager appManager,
            @Nullable SettingsInfo settingsInfo,
            Path dataDir,
            @Nullable TranscodeSettings transcodeSettings,
            ReplayGainConfig replayGainConfig
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.setValign(Align.FILL);
        this.setHalign(Align.FILL);
        this.clearSongCacheButton = ButtonRow.builder().setTitle(tr("Clear song cache")).build();
        this.clearSongCacheButton.addCssClass(destructiveAction.className());
        this.clearSongCacheButton.onActivated(() -> appManager.handleAction(new PlayerAction.ClearSongCache()));

        this.clearThumbnailCacheButton = ButtonRow.builder().setTitle(tr("Clear thumbnail cache")).build();
        this.clearThumbnailCacheButton.addCssClass(destructiveAction.className());
        this.clearThumbnailCacheButton.onActivated(() -> appManager.handleAction(new PlayerAction.ClearThumbnailCache()));

        this.clearLyricsCacheButton = ButtonRow.builder().setTitle(tr("Clear lyrics cache")).build();
        this.clearLyricsCacheButton.addCssClass(destructiveAction.className());
        this.clearLyricsCacheButton.onActivated(() -> appManager.handleAction(new PlayerAction.ClearLyricsCache()));

        this.localSettings = new PreferencesGroup();
        this.localSettings.setTitle(tr("Local Settings"));
        this.localSettings.setSeparateRows(false);
        this.localSettings.add(clearSongCacheButton);
        this.localSettings.add(clearThumbnailCacheButton);
        this.localSettings.add(clearLyricsCacheButton);

        var formats = Arrays.stream(ServerClient.TranscodeFormat.values()).toList();
        var model = new StringList();
        formats.forEach(val -> {
            if (val == ServerClient.TranscodeFormat.source) {
                model.append(tr("Source"));
            } else {
                model.append(val.name());
            }
        });
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
        this.audioFormatCombo.setTitle(tr("Audio format"));
        this.audioFormatCombo.setModel(model);
        var initialFormat = transcodeSettings != null ? transcodeSettings.format() : ServerClient.TranscodeFormat.opus;
        this.audioFormatCombo.setSelected(formats.indexOf(initialFormat));

        var bitrateModelList = new StringList();
        bitRates.stream().map(value -> switch (value) {
            case SourceQuality _ -> tr("Source");
            case MaximumBitrate(var bitrate) -> String.format("%d", bitrate);
        }).forEach(bitrateModelList::append);
        this.audioBitrateCombo = new ComboRow();
        this.audioBitrateCombo.setTitle(tr("Max bitrate"));
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
                    this.appManager.handleAction(new PlayerAction.SaveTranscodeFormat(selected));
                }
        );
        this.audioBitrateCombo.onNotify(
                "selected", _ -> {
                    var fmt = formats.get(this.audioFormatCombo.getSelected());
                    var bitrate = bitRates.get(this.audioBitrateCombo.getSelected());
                    var selected = new TranscodeSettings(fmt, bitrate);
                    log.info("Selected transcode settings: {}", selected);
                    this.appManager.handleAction(new PlayerAction.SaveTranscodeFormat(selected));
                }
        );

        this.serverInformation = new PreferencesGroup();
        this.serverInformation.setTitle(tr("Server information"));
        this.serverInformation.setSeparateRows(false);
        this.serverTypeLabel = newRow(tr("Type"));
        this.serverVersionLabel = newRow(tr("Version"));
        this.apiVersionLabel = newRow(tr("API Version"));
        this.serverOpenSubsonic = newRow(tr("OpenSubsonic support"));
        this.serverInformation.add(serverTypeLabel);
        this.serverInformation.add(serverVersionLabel);
        this.serverInformation.add(apiVersionLabel);
        this.serverInformation.add(serverOpenSubsonic);
        this.serverOpenSubsonicGroup.setTitle(tr("OpenSubsonic extensions"));
        this.serverOpenSubsonicFeatureList.forEach(this.serverOpenSubsonicGroup::add);
        this.serverOpenSubsonicGroup.setVisible(false);

        this.transcodeSettings = new PreferencesGroup();
        this.transcodeSettings.setTitle(tr("Transcode Settings"));
        this.transcodeSettings.setSeparateRows(false);
        this.transcodeSettings.add(audioFormatCombo);
        this.transcodeSettings.add(audioBitrateCombo);

        // ReplayGain (loudness normalization) settings, stored per server. Seeded from the
        // caller (mirroring transcodeSettings); AppManager remains the owner and pushes changes
        // to the player.
        var rgConfig = replayGainConfig != null ? replayGainConfig : ReplayGainConfig.defaultConfig();
        this.rgEnabledSwitch = SwitchRow.builder()
                .setTitle(tr("Enable ReplayGain"))
                .setSubtitle(tr("Normalize playback loudness across tracks"))
                .build();
        this.rgEnabledSwitch.setActive(rgConfig.enabled());

        var rgModeModel = new StringList();
        rgModeModel.append(tr("Track"));
        rgModeModel.append(tr("Album"));
        this.rgModeCombo = new ComboRow();
        this.rgModeCombo.setTitle(tr("Normalization mode"));
        this.rgModeCombo.setSubtitle(tr("Album mode preserves an album's internal dynamics"));
        this.rgModeCombo.setModel(rgModeModel);
        this.rgModeCombo.setSelected(rgConfig.mode() == ReplayGainConfig.Mode.ALBUM ? 1 : 0);

        this.rgPreAmpSpin = SpinRow.withRange(-15.0, 15.0, 0.5);
        this.rgPreAmpSpin.setTitle(tr("Pre-amp (dB)"));
        this.rgPreAmpSpin.setDigits(1);
        this.rgPreAmpSpin.setValue(rgConfig.preAmpDb());

        this.rgFallbackSpin = SpinRow.withRange(-15.0, 15.0, 0.5);
        this.rgFallbackSpin.setTitle(tr("Fallback gain (dB)"));
        this.rgFallbackSpin.setSubtitle(tr("Applied to tracks without ReplayGain data"));
        this.rgFallbackSpin.setDigits(1);
        this.rgFallbackSpin.setValue(rgConfig.fallbackGainDb());

        this.replayGainSettings = new PreferencesGroup();
        this.replayGainSettings.setTitle(tr("ReplayGain"));
        this.replayGainSettings.setSeparateRows(false);
        this.replayGainSettings.add(rgEnabledSwitch);
        this.replayGainSettings.add(rgModeCombo);
        this.replayGainSettings.add(rgPreAmpSpin);
        this.replayGainSettings.add(rgFallbackSpin);

        // Wire change handlers AFTER setting initial values to avoid a spurious save on load.
        this.rgEnabledSwitch.onNotify("active", _ -> {
            applyReplayGainSensitivity();
            saveReplayGain();
        });
        this.rgModeCombo.onNotify("selected", _ -> saveReplayGain());
        this.rgPreAmpSpin.getAdjustment().onValueChanged(this::saveReplayGain);
        this.rgFallbackSpin.getAdjustment().onValueChanged(this::saveReplayGain);
        applyReplayGainSensitivity();

        this.librarySyncRow = ButtonRow.builder()
                .setTitle(tr("Full scan"))
                .setActivatable(true)
                .setTooltipText(tr("Start server scan for new songs and folders"))
                .build();
        this.librarySyncRow.setStartIconName(Icons.Search.getIconName());
        this.librarySyncRow.onActivated(() ->
                this.appManager.handleAction(new PlayerAction.TriggerServerScan(false))
        );

        this.libraryQuickScanRow = ButtonRow.builder()
                .setTitle(tr("Quick scan"))
                .setActivatable(true)
                .setTooltipText(tr("Start server quick scan for new songs and folders"))
                .build();
        this.libraryQuickScanRow.setStartIconName(Icons.RefreshView.getIconName());
        this.libraryQuickScanRow.onActivated(() ->
                this.appManager.handleAction(new PlayerAction.TriggerServerScan(true))
        );

        this.libraryLastScanAtRow = newRow(tr("Last Scanned at"));
        this.libraryScanStatus = newRow(tr("Status"));
        this.librarySongsRow = newRow(tr("Server songs"));
        this.librarySongsRow.setSubtitle("…");
        this.serverLibrary = new PreferencesGroup();
        this.serverLibrary.setTitle(tr("Library"));
        this.serverLibrary.setSeparateRows(false);
        this.serverLibrary.add(libraryScanStatus);
        this.serverLibrary.add(librarySongsRow);
        this.serverLibrary.add(libraryQuickScanRow);
        this.serverLibrary.add(librarySyncRow);

        this.localSongsRow = newRow(tr("Local songs"));
        this.localSongsRow.setSubtitle("…");

        this.localSongsTriggerSync = ButtonRow.builder()
                .setTitle(tr("Sync library"))
                .setActivatable(true)
                .setTooltipText(tr("Syncs metadata for offline use"))
                .build();
        this.localSongsTriggerSync.setStartIconName(Icons.FolderDownload.getIconName());
        this.localSongsTriggerSync.onActivated(() -> {
            this.localSongsTriggerSync.setSensitive(false);
            this.appManager.handleAction(new PlayerAction.SyncDatabase()).whenComplete((v, err) ->
                    Utils.runOnMainThread(() -> {
                        boolean offline = this.appManager.getState().networkState().status() == NetworkStatus.OFFLINE;
                        this.localSongsTriggerSync.setSensitive(!offline);
                        this.refreshLocalCount();
                    })
            );
        });

        this.syncGroup = new PreferencesGroup();
        this.syncGroup.setTitle(tr("Local Library"));
        this.syncGroup.setSeparateRows(false);
        this.syncGroup.add(localSongsRow);
        this.syncGroup.add(localSongsTriggerSync);

        applyNetworkSensitivity(this.appManager.getState().networkState().status());
        this.networkStateListener = state -> {
            var status = state.networkState().status();
            Utils.runOnMainThread(() -> applyNetworkSensitivity(status));
        };
        this.appManager.addOnStateChanged(this.networkStateListener);
        this.onDestroy(() -> this.appManager.removeOnStateChanged(this.networkStateListener));

        this.form = new ServerConfigForm(
                settingsInfo,
                dataDir,
                this.appManager::handleAction
        );

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(this.form);
        this.centerBox.append(this.serverInformation);
        this.centerBox.append(this.serverOpenSubsonicGroup);
        this.centerBox.append(this.serverLibrary);
        this.centerBox.append(this.syncGroup);
        this.centerBox.append(this.transcodeSettings);
        this.centerBox.append(this.replayGainSettings);
        this.centerBox.append(this.localSettings);

        var clamp = new Clamp();
        clamp.setMaximumSize(600);
        clamp.setChild(this.centerBox);
        var scroll = new ScrolledWindow();
        scroll.setChild(clamp);
        scroll.setHexpand(true);
        scroll.setVexpand(true);
        scroll.setPropagateNaturalWidth(true);
        scroll.setPropagateNaturalHeight(true);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(scroll);
        this.refresh();
        this.refreshLocalCount();
    }

    private void applyReplayGainSensitivity() {
        boolean enabled = this.rgEnabledSwitch.getActive();
        this.rgModeCombo.setSensitive(enabled);
        this.rgPreAmpSpin.setSensitive(enabled);
        this.rgFallbackSpin.setSensitive(enabled);
    }

    private void saveReplayGain() {
        var mode = this.rgModeCombo.getSelected() == 1
                ? ReplayGainConfig.Mode.ALBUM
                : ReplayGainConfig.Mode.TRACK;
        var config = new ReplayGainConfig(
                this.rgEnabledSwitch.getActive(),
                mode,
                this.rgPreAmpSpin.getValue(),
                this.rgFallbackSpin.getValue()
        );
        this.appManager.handleAction(new PlayerAction.SaveReplayGainConfig(config));
    }

    private void refresh() {
        Utils.doAsync(() -> this.appManager.useClient(ServerClient::getServerInfo))
                .thenAccept(serverInfo -> this.update(serverInfo));
    }

    private void refreshLocalCount() {
        Utils.doAsync(this.appManager::getLocalSongCount).thenAccept(count ->
                Utils.runOnMainThread(() -> this.localSongsRow.setSubtitle("%,d".formatted(count)))
        );
    }

    private void update(ServerClient.ServerInfo serverInfo) {
        Utils.runOnMainThread(() -> {
            boolean isNavidrome = serverInfo.serverType().map(type -> type.toLowerCase().contains("navidrome")).orElse(false);
            this.serverTypeLabel.setSubtitle(serverInfo.serverType().orElse(tr("Unknown")));
            this.serverVersionLabel.setSubtitle(serverInfo.serverVersion().orElse(tr("Unknown")));
            this.apiVersionLabel.setSubtitle(serverInfo.apiVersion());
            this.serverOpenSubsonic.setSubtitle(serverInfo.isOpenSubsonic() ? tr("Yes") : tr("No"));
            if (serverInfo.openSubsonicExtensions().isPresent()) {
                var extensions = serverInfo.openSubsonicExtensions().get();
                this.serverOpenSubsonicFeatureList.forEach(this.serverOpenSubsonicGroup::remove);
                this.serverOpenSubsonicFeatureList = extensions.list().stream()
                        .map(ext -> {
                            var row = newRow(ext.name());
                            var subtitle = ext.versions().stream()
                                    .map(v -> "v%d".formatted(v))
                                    .collect(Collectors.joining(", "));
                            row.setSubtitle(subtitle);
                            return row;
                        })
                        .toList();
                this.serverOpenSubsonicFeatureList.forEach(this.serverOpenSubsonicGroup::add);
                this.serverOpenSubsonicGroup.setVisible(true);
            } else {
                this.serverOpenSubsonicGroup.setVisible(false);
                this.serverOpenSubsonicFeatureList.forEach(this.serverOpenSubsonicGroup::remove);
                this.serverOpenSubsonicFeatureList = List.of();
            }
            this.librarySongsRow.setSubtitle("%,d".formatted(serverInfo.songCount()));
            this.libraryQuickScanRow.setSensitive(isNavidrome);
            serverInfo.lastScan().ifPresent(then -> {
                this.libraryLastScanAtRow.setSubtitle(then.toString());
            });
            this.libraryLastScanAtRow.setVisible(serverInfo.lastScan().isPresent());
        });
    }

    private void applyNetworkSensitivity(NetworkStatus status) {
        boolean offline = status == NetworkStatus.OFFLINE;
        this.localSongsTriggerSync.setSensitive(!offline);
        this.librarySyncRow.setSensitive(!offline);
    }

    private static Image buildIcon(String iconName) {
        var icon = Image.fromIconName(iconName);
        icon.setPixelSize(16);
        icon.setSizeRequest(32, -1);
        icon.setHalign(Align.CENTER);
        icon.setValign(Align.CENTER);
        return icon;
    }

    static ActionRow newRow(String title) {
        var row = new ActionRow();
        row.setTitle(title);
        return row;
    }

    public void setSettingsInfo(@Nullable SettingsInfo s) {
        this.form.setSettingsInfo(s);
    }
}
