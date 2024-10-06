package com.github.subsound.integration.platform.mpriscontroller;

import com.github.subsound.app.state.AppManager;
import com.github.subsound.integration.ServerClient;
import com.softwaremill.jox.Channel;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.DBusListType;
import org.freedesktop.dbus.types.DBusMapType;
import org.freedesktop.dbus.types.Variant;
import org.jetbrains.annotations.Nullable;
import org.mpris.MediaPlayer2.MediaPlayer2;
import org.mpris.MediaPlayer2.MediaPlayer2Player;
import org.mpris.MediaPlayer2.TrackList.TrackId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.interfaceName;
import static com.github.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.toListVariant;
import static com.github.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.toMicroseconds;

public class MPrisController implements MediaPlayer2, MediaPlayer2Player, AppManager.StateListener, Properties {
    private final static Logger log = LoggerFactory.getLogger(MPrisController.class);
    private final AppManager appManager;
    private final Channel<PropertiesChanged> dbusMessageChannel = new Channel<>();
    private final MprisApplicationProperties mprisApplicationProperties;
    private final AtomicReference<MPRISPlayerState> playerState = new AtomicReference<>();

    public MPrisController(AppManager appManager) {
        this.appManager = appManager;
        this.appManager.addOnStateChanged(this);
        this.mprisApplicationProperties = new MprisApplicationProperties();
    }

    private final CountDownLatch countDownLatch = new CountDownLatch(1);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public void stop() {
        if (isShutdown.compareAndSet(false, true)) {
            // remove receiving state updates:
            this.appManager.removeOnStateChanged(this);
            // unblocks the run() method:
            this.countDownLatch.countDown();
            this.dbusMessageChannel.done();
        }
    }

    public void run() {
        var builder = DBusConnectionBuilder.forSessionBus().withShared(false);
        // Set all thread handlers to single threads. See also:
        // https://github.com/hypfvieh/dbus-java/issues/220
        // https://github.com/flatpak/xdg-dbus-proxy/issues/46
        builder.receivingThreadConfig().withErrorHandlerThreadCount(1);
        builder.receivingThreadConfig().withSignalThreadCount(1);
        builder.receivingThreadConfig().withMethodReturnThreadCount(1);
        builder.receivingThreadConfig().withMethodCallThreadCount(1);
        try (DBusConnection conn = builder.build()) {
            conn.exportObject("/org/mpris/MediaPlayer2", this);
            conn.requestBusName("org.mpris.MediaPlayer2.com.github.Subsound");
            while (!isShutdown.get()) {
                var msg = dbusMessageChannel.receive();
                if (msg != null) {
                    conn.sendMessage(msg);
                }
            }
            countDownLatch.await();
        } catch (DBusException | IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void Raise() {
        log.info("Raise");
    }

    @Override
    public void Quit() {
        log.info("Quit");
    }

    @Override
    public String getObjectPath() {
        return "/org/mpris/MediaPlayer2";
    }

    @Override
    public void Next() {
        log.info("Next");
        this.appManager.next();
    }

    @Override
    public void Previous() {
        log.info("Previous");
        this.appManager.prev();
    }

    @Override
    public void Pause() {
        log.info("Pause");
        this.appManager.pause();
    }

    @Override
    public void PlayPause() {
        log.info("PlayPause");
        this.appManager.playPause();
    }

    @Override
    public void Stop() {
        log.info("Stop");
        this.appManager.pause();
    }

    @Override
    public void Play() {
        log.info("Play");
        this.appManager.play();
    }

    @Override
    public void Seek(long microseconds) {
        log.info("Seek");
    }

    @Override
    public void OpenUri(String uri) {
        log.info("OpenUri");
    }

    @Override
    public void SetPosition(String trackId, long position) {
        log.info("SetPosition");
    }

    @Override
    public void onStateChanged(AppManager.AppState next) {
        var prev = this.playerState.get();
        if (prev == null) {
            MPRISPlayerState newState = toMprisState(next);
            this.playerState.set(newState);
            return;
        }
        MPRISPlayerState old = prev;
        MPRISPlayerState newState = toMprisState(next);
        this.playerState.set(newState);
        Optional<PropertiesChanged> changed = newState.diff(old, true);
        if (changed.isPresent()) {
            try {
                this.dbusMessageChannel.sendOrClosed(changed.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private MPRISPlayerState toMprisState(AppManager.AppState next) {
        var metadata = next.nowPlaying().map(np -> toMprisMetadata(np)).orElse(null);
        return new MPRISPlayerState(
                next.player().state().toMpris(),
                LoopStatus.None,
                1.0,
                false,
                metadata,
                next.player().volume(),
                // TODO(mpris): song position
                Duration.ZERO,
                1.0,
                1.0,
                true,
                true,
                true,
                true,
                false,
                true
        );
    }

    private MPRISMetadata toMprisMetadata(AppManager.NowPlaying np) {
        Long discNumber = np.song().discNumber().map(Long::valueOf).orElse(null);
        return new MPRISMetadata(
                new TrackId(np.song().id()),
                np.song().duration(),
                List.of(np.song().artist()),
                List.of(np.song().artist()),
                np.song().album(),
                np.song().title(),
                discNumber,
                np.song().coverArt().map(ServerClient.CoverArt::coverArtFilePath).map(Path::toUri).orElse(null),
                null
        );
    }


    public static <T> Variant<T> ofVariant(T value) {
        return new Variant<>(value);
    }

    @Override
    public <A> A Get(String _interfaceName, String _propertyName) {
        return switch (_interfaceName) {
            case MprisApplicationProperties.dbusInterfaceName -> this.mprisApplicationProperties.Get(_interfaceName, _propertyName);
            case MPRISPlayerState.interfaceName -> this.playerState.get().Get(_interfaceName, _propertyName);
            default -> throw new IllegalArgumentException("Get: Unexpected value: " + _interfaceName);
        };
    }

    @Override
    public <A> void Set(String _interfaceName, String _propertyName, A _value) {
        switch (_interfaceName) {
            case MprisApplicationProperties.dbusInterfaceName -> this.mprisApplicationProperties.Set(_interfaceName, _propertyName, _value);
        };
    }

    @Override
    public Map<String, Variant<?>> GetAll(String _interfaceName) {
        return switch (_interfaceName) {
            case MprisApplicationProperties.dbusInterfaceName -> this.mprisApplicationProperties.GetAll(_interfaceName);
            case MPRISPlayerState.interfaceName -> this.playerState.get().GetAll(_interfaceName);
            default -> throw new IllegalArgumentException("GetAll: Unexpected value: " + _interfaceName);
        };
    }

    // See https://specifications.freedesktop.org/mpris-spec/2.2/Player_Interface.html
    public static class MPRISPlayerState implements Properties {
        public static final String objectPath = "/org/mpris/MediaPlayer2";
        public static final String interfaceName = "org.mpris.MediaPlayer2.Player";

        private final PlaybackStatus playbackStatus;
        private final LoopStatus loopStatus;
        private final double rate;
        private final boolean shuffle;
        private final MPRISMetadata metadata;
        private final double volume;
        private final Duration position;
        private final double minimumRate;
        private final double maximumRate;
        private final boolean canGoNext;
        private final boolean canGoPrevious;
        private final boolean canPlay;
        private final boolean canPause;
        private final boolean canSeek;
        private final boolean canControl;
        private final Map<String, Variant<?>> variants;

        public MPRISPlayerState(
                PlaybackStatus playbackStatus,
                LoopStatus loopStatus,
                double rate,
                boolean shuffle,
                MPRISMetadata metadata,
                double volume,
                Duration position,
                double minimumRate,
                double maximumRate,
                boolean canGoNext,
                boolean canGoPrevious,
                boolean canPlay,
                boolean canPause,
                boolean canSeek,
                boolean canControl
        ) {
            this.playbackStatus = playbackStatus;
            this.loopStatus = loopStatus;
            this.rate = rate;
            this.shuffle = shuffle;
            this.metadata = metadata;
            this.volume = volume;
            this.position = position;
            this.minimumRate = minimumRate;
            this.maximumRate = maximumRate;
            this.canGoNext = canGoNext;
            this.canGoPrevious = canGoPrevious;
            this.canPlay = canPlay;
            this.canPause = canPause;
            this.canSeek = canSeek;
            this.canControl = canControl;
            this.variants = asVariant();
        }

        public Map<String, Variant<?>> variant() {
            return this.variants;
        }

        private Map<String, Variant<?>> asVariant() {
            return Map.ofEntries(
                    Map.entry("PlaybackStatus", playbackStatus.variant()),
                    Map.entry("LoopStatus", loopStatus.variant()),
                    Map.entry("Rate", ofVariant(rate)),
                    Map.entry("Shuffle", ofVariant(shuffle)),
                    Map.entry("Metadata", toMapVariant(metadata)),
                    Map.entry("Volume", ofVariant(volume)),
                    Map.entry("Position", ofVariant(toMicroseconds(position))),
                    Map.entry("MinimumRate", ofVariant(minimumRate)),
                    Map.entry("MaximumRate", ofVariant(maximumRate)),
                    Map.entry("CanGoNext", ofVariant(canGoNext)),
                    Map.entry("CanGoPrevious", ofVariant(canGoPrevious)),
                    Map.entry("CanPlay", ofVariant(canPlay)),
                    Map.entry("CanPause", ofVariant(canPause)),
                    Map.entry("CanSeek", ofVariant(canSeek)),
                    Map.entry("CanControl", ofVariant(canControl))
            );
        }

        public static Variant<?> toMapVariant(@Nullable MPRISMetadata metadata) {
            if (metadata == null) {
                return toMapVariant(Map.of());
            }
            return toMapVariant(metadata.variant());
        }

        public static final DBusMapType VARIANT_MAP_STRING_VARIANT_TYPE = new DBusMapType(String.class, Variant.class);

        public static Variant<?> toMapVariant(Map<String, Variant<?>> map) {
            return new Variant<>(map, VARIANT_MAP_STRING_VARIANT_TYPE);
        }

        public static final DBusListType VARIANT_LIST_STRING_TYPE = new DBusListType(String.class);

        public static Variant<?> toListVariant(@Nullable List<String> list) {
            if (list == null) {
                return new Variant<>(List.of(), VARIANT_LIST_STRING_TYPE);
            }
            return new Variant<>(list, VARIANT_LIST_STRING_TYPE);
        }

        public static long toMicroseconds(Duration position) {
            return position.toNanos() / 1000;
        }

        public Optional<PropertiesChanged> diff(MPRISPlayerState old, boolean skipPosition) {
            var oldVariant = old.variant();
            var newVariant = this.variant();
            var diffs = diff(oldVariant, newVariant);
            if (skipPosition) {
                diffs.remove("Position");
            }
            if (diffs.isEmpty()) {
                return Optional.empty();
            } else {
                try {
                    var changes = new PropertiesChanged(objectPath, interfaceName, diffs, List.of());
                    return Optional.of(changes);
                } catch (DBusException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // Returns the map with the entries that were added or changed
        public static Map<String, Variant<?>> diff(
                Map<String, Variant<?>> oldVariant,
                Map<String, Variant<?>> newVariant
        ) {
            var map = new HashMap<String, Variant<?>>();
            for (Map.Entry<String, Variant<?>> newVal : newVariant.entrySet()) {
                var oldVal = oldVariant.get(newVal.getKey());
                if (!oldVariant.containsKey(newVal.getKey()) || !newVal.getValue().equals(oldVal.getValue())) {
                    map.put(newVal.getKey(), newVal.getValue());
                }
            }
            return map;
        }

        @Override
        public <A> A Get(String _interfaceName, String _propertyName) {
            //noinspection unchecked
            return (A) this.variants.get(_propertyName);
        }

        @Override
        public <A> void Set(String _interfaceName, String _propertyName, A _value) {
            log.warn("MPRISPlayerState: ignoring Set {} {} {} ", _interfaceName, _propertyName, _value);
        }

        @Override
        public Map<String, Variant<?>> GetAll(String _interfaceName) {
            return this.variants;
        }

        @Override
        public String getObjectPath() {
            return objectPath;
        }
    }

    // See https://specifications.freedesktop.org/mpris-spec/2.2/Track_List_Interface.html#Mapping:Metadata_Map
    record MPRISMetadata(
            TrackId trackId,
            Duration length,
            List<String> artist,
            List<String> albumArtist,
            String album,
            String title,
            @Nullable Long discNumber,
            @Nullable URI artUrl,
            @Nullable String lyrics
    ) {

        public Map<String, Variant<?>> variant() {
            var map = new HashMap<String, Variant<?>>();
            map.put("mpris:trackid", ofVariant(trackId.trackid()));
            map.put("mpris:length", ofVariant(toMicroseconds(length)));
            map.put("xesam:artist", toListVariant(artist));
            map.put("xesam:albumArtist", toListVariant(albumArtist));
            map.put("xesam:album", ofVariant(album));
            map.put("xesam:title", ofVariant(title));
            if (discNumber != null) {
                map.put("xesam:discNumber", ofVariant(discNumber));
            }
            if (artUrl != null) {
                var artUri = artUrl.toString().replace("file:", "file://");
                //val artUri = "file://${artUrl.absolutePath}"
                map.put("mpris:artUrl", ofVariant(artUri));
            }
            if (lyrics != null) {
                map.put("xesam:asText", ofVariant(lyrics));
            }
            return map;
        }
    }

    public static class MprisApplicationProperties implements Properties {
        private static final String dbusInterfaceName = "org.mpris.MediaPlayer2";
        private final boolean canQuit = true;
        private final boolean fullscreen = false;
        private final boolean canSetFullscreen = false;
        private final boolean canRaise = true;
        private final boolean hasTrackList = false;
        private final String identity = "Subsound";
        private final String desktopEntry = "com.github.Subsound";
        private final List<String> supportedUriSchemes = List.of(
                //"file", "https" // TODO(mpris)
        );
        private final List<String> supportedMimeTypes = List.of(
                //"audio/mpeg", "audio/mp3"// TODO(mpris)
        );
        private final Map<String, Variant<?>> variants;

        public MprisApplicationProperties() {
            this.variants = Map.of(
                    "CanQuit", ofVariant(canQuit),
                    "Fullscreen", ofVariant(fullscreen),
                    "CanSetFullscreen", ofVariant(canSetFullscreen),
                    "CanRaise", ofVariant(canRaise),
                    "HasTrackList", ofVariant(hasTrackList),
                    "Identity", ofVariant(identity),
                    "DesktopEntry", ofVariant(desktopEntry),
                    "SupportedUriSchemes", toListVariant(supportedUriSchemes),
                    "SupportedMimeTypes", toListVariant(supportedMimeTypes)
            );
        }

        @Override
        public <A> A Get(String _interfaceName, String _propertyName) {
            log.info("{}: Get {}/{}", interfaceName, _interfaceName, _propertyName);
            //noinspection unchecked
            return (A) GetAll(_interfaceName).get(_propertyName).getValue();
        }

        @Override
        public <A> void Set(String _interfaceName, String _propertyName, A _value) {
            log.info("{}: Set {}/{} value={}", interfaceName, _interfaceName, _propertyName, _value);
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return this.variants;
        }

        @Override
        public String getObjectPath() {
            return "/org/mpris/MediaPlayer2";
        }
    }
}
