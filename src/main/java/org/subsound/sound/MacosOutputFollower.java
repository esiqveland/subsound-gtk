package org.subsound.sound;

import org.freedesktop.gstreamer.gst.Device;
import org.freedesktop.gstreamer.gst.DeviceMonitor;
import org.freedesktop.gstreamer.gst.Element;
import org.freedesktop.gstreamer.gst.ElementFactory;
import org.freedesktop.gstreamer.gst.Format;
import org.freedesktop.gstreamer.gst.Gst;
import org.freedesktop.gstreamer.gst.SeekFlags;
import org.freedesktop.gstreamer.gst.State;
import org.freedesktop.gstreamer.gst.Structure;
import org.javagi.base.Out;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;

/**
 * macOS-only: installs {@code osxaudiosink} as the playbin audio-sink and keeps it pointed at the
 * system default output.
 *
 * <p>We poll a DeviceMonitor rather than watching its bus: GStreamer's osxaudiodeviceprovider only
 * posts events on device-list changes (add/remove), so switching the default between
 * already-connected devices in System Settings is invisible to the bus. The monitor is deliberately
 * left <em>unstarted</em> and used purely as a synchronous probe — an unstarted monitor re-probes
 * CoreAudio on each {@code getDevices()}, so it reflects the live default including such a switch.
 */
final class MacosOutputFollower {
    private static final Logger log = LoggerFactory.getLogger(MacosOutputFollower.class);
    private static final Duration DEVICE_POLL_INTERVAL = Duration.ofSeconds(2);

    /** The bits of the player this follower needs, kept narrow so the dependency stays one-way. */
    interface PlayerHooks {
        /** True once a uri is loaded; there is nothing to follow otherwise. */
        boolean hasSource();

        boolean isPlaying();

        Duration currentPosition();

        void pause();
    }

    private final Element playbinEl;
    private final PlayerHooks hooks;
    private final DeviceMonitor deviceMonitor;
    private final Thread pollThread;
    // unique-id of the default output we last followed; polling compares against this.
    private volatile String lastDefaultId;

    /**
     * Installs the sink and starts following the default output. Returns null when this platform
     * cannot support it: the sink could not be created, or the GStreamer runtime is older than
     * 1.28 (which is where the provider started reporting {@code is-default}, without which we
     * cannot tell which output is the default).
     */
    static MacosOutputFollower install(Element playbinEl, PlayerHooks hooks) {
        Element osxaudiosink = ElementFactory.make("osxaudiosink", "audio-output");
        if (osxaudiosink == null) {
            log.info("unable to set audio-sink: osxaudiosink on macos");
            return null;
        }
        osxaudiosink.set("device", 0, null); // 0 = default device
        playbinEl.set("audio-sink", osxaudiosink, null);
        log.info("set audio-sink: osxaudiosink");

        if (!isGstAtLeast(1, 28)) {
            log.info(
                    "Gst.DeviceMonitor: audio-output monitoring requires GStreamer >= 1.28, have '{}'; skipping",
                    Gst.versionString()
            );
            return null;
        }
        return new MacosOutputFollower(playbinEl, hooks);
    }

    private MacosOutputFollower(Element playbinEl, PlayerHooks hooks) {
        this.playbinEl = playbinEl;
        this.hooks = hooks;
        this.deviceMonitor = new DeviceMonitor();
        this.deviceMonitor.addFilter("Audio/Sink", null);
        this.lastDefaultId = resolveDefaultOutputId();
        log.info("Gst.DeviceMonitor: initial default output unique-id={}", lastDefaultId);
        this.pollThread = new Thread(this::pollDefaultOutput, "audio-device-poller");
        this.pollThread.setDaemon(true);
        this.pollThread.start();
    }

    /**
     * Stop the poller. The DeviceMonitor is never started (we only use it to probe), so there is
     * nothing to stop on it.
     */
    void stop() {
        pollThread.interrupt();
    }

    /** True if the linked/loaded GStreamer runtime is at least major.minor. */
    private static boolean isGstAtLeast(int major, int minor) {
        Out<Integer> maj = new Out<>();
        Out<Integer> min = new Out<>();
        Out<Integer> micro = new Out<>();
        Out<Integer> nano = new Out<>();
        Gst.version(maj, min, micro, nano);
        return maj.get() > major || (maj.get() == major && min.get() >= minor);
    }

    /**
     * unique-id of a device. On GstOsxAudioDevice this exists both as a GObject property and in
     * the properties Structure; we read the Structure so it works for any provider.
     */
    private static String uniqueId(Device d) {
        Structure props = d.getProperties();
        return props != null ? props.getString("unique-id") : null;
    }

    /**
     * Whether the device is the current system default. This is NOT a GObject property on
     * GstOsxAudioDevice (getProperty("is-default") throws); it lives only in the device's
     * properties Structure. Returns null if absent. Note the flag is racy at add/change time —
     * macOS flips the default just after a device appears — so follow-default logic must re-read.
     */
    private static Boolean isDefault(Device d) {
        Structure props = d.getProperties();
        if (props == null || !props.hasField("is-default")) {
            return null;
        }
        Out<Boolean> out = new Out<>();
        return props.getBoolean("is-default", out) ? out.get() : null;
    }

    /** unique-id of the current system default Audio/Sink, or null if none/undeterminable. */
    private String resolveDefaultOutputId() {
        var devices = deviceMonitor.getDevices();
        if (devices == null) {
            return null;
        }
        for (Device d : devices) {
            if (Boolean.TRUE.equals(isDefault(d))) {
                return uniqueId(d);
            }
        }
        return null;
    }

    /**
     * Poll loop (daemon thread "audio-device-poller"): every {@link #DEVICE_POLL_INTERVAL}, probe
     * the current default output and re-open the pipeline onto it if it changed. Polling is the only
     * way to notice a default switch between already-connected devices (no bus event fires for that).
     * Runs off the main/loop thread, so the blocking pipeline re-open in cycleAudioOutput() is safe.
     */
    private void pollDefaultOutput() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(DEVICE_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                followDefaultIfChanged();
            } catch (Throwable t) {
                log.error("audio-device-poller: failed", t);
            }
        }
        log.info("audio-device-poller: stopped");
    }

    /**
     * If the system default output changed since we last followed it, either follow it or pause:
     * <ul>
     *   <li>If the previously-active output device is <b>gone</b> from the list, it disconnected
     *       (e.g. AirPods). macOS falls back to the built-in speakers; rather than suddenly blasting
     *       them, we pause — matching native behaviour.</li>
     *   <li>Otherwise the default just moved (Settings switch, or a newly-connected device became
     *       default) while the old device is still present, so we re-open onto the new default.</li>
     * </ul>
     */
    private void followDefaultIfChanged() {
        if (!hooks.hasSource()) {
            return;
        }
        var devices = deviceMonitor.getDevices(); // fresh probe (unstarted monitor)
        if (devices == null) {
            return;
        }
        String current = null;
        boolean lastStillPresent = false;
        for (Device d : devices) {
            String id = uniqueId(d);
            if (id == null) {
                continue;
            }
            if (id.equals(lastDefaultId)) {
                lastStillPresent = true;
            }
            if (Boolean.TRUE.equals(isDefault(d))) {
                current = id;
            }
        }
        if (current == null || current.equals(lastDefaultId)) {
            return;
        }
        String previous = lastDefaultId;
        lastDefaultId = current;
        if (previous != null && !lastStillPresent) {
            log.info("🎵 Active audio output {} disconnected; pausing instead of switching to {}",
                    previous, current);
            hooks.pause();
        } else {
            log.info("🎵 Default output changed: {} -> {}; following", previous, current);
            cycleAudioOutput();
        }
    }

    /**
     * Re-open the pipeline so osxaudiosink (device=0) reconnects to the current system default
     * output, preserving playback position. osxaudiosink only re-resolves the default when it
     * reopens its device, which requires passing through READY. Bouncing just the sink element
     * leaves it without a running clock/segment and playback stalls, so we bounce the whole
     * playbin (PLAYING→READY→PAUSED to preroll on the new default, seek back, then PLAYING). This
     * is the same re-init a track skip does, minus the track change.
     *
     * Called from the poll thread (never the main/loop thread), so blocking on getState() for
     * preroll here is safe — the main loop stays free to deliver ASYNC_DONE.
     */
    private void cycleAudioOutput() {
        if (!hooks.hasSource()) {
            return;
        }
        boolean wasPlaying = hooks.isPlaying();
        Duration pos = hooks.currentPosition();

        // Bounce the pipeline through READY so the sink closes and reopens on the new default.
        this.playbinEl.setState(State.READY);
        this.playbinEl.setState(State.PAUSED);
        // Block until PAUSED preroll completes so the seek below lands accurately. Safe here: this
        // is the switch thread, not the main loop.
        Out<State> stateOut = new Out<>();
        Out<State> pendingOut = new Out<>();
        this.playbinEl.getState(stateOut, pendingOut, Gst.CLOCK_TIME_NONE);
        this.playbinEl.seekSimple(Format.TIME, Set.of(SeekFlags.ACCURATE, SeekFlags.FLUSH), pos.toNanos());
        if (wasPlaying) {
            this.playbinEl.setState(State.PLAYING);
        }
        log.info("🎵 Switched audio output; resumed at {}s (wasPlaying={}) default-now={}",
                pos.getSeconds(), wasPlaying, resolveDefaultOutputId());
    }
}
