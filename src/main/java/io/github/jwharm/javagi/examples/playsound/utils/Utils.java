package io.github.jwharm.javagi.examples.playsound.utils;

import org.gnome.glib.GLib;
import org.gnome.glib.SourceOnceFunc;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PropagationLimit;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Widget;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;


public class Utils {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();

    public static <T> CompletableFuture<T> doAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, EXECUTOR);
    }

    public static CompletableFuture<Void> doAsync(Runnable supplier) {
        return CompletableFuture.runAsync(supplier, EXECUTOR);
    }

    public static void runOnMainThread(SourceOnceFunc fn) {
        GLib.idleAddOnce(fn);
    }

    public static String sha256(String value) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(value.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static long copyLarge(InputStream input, OutputStream output) throws IOException {
        return copyLarge(input, output, new byte[8192]);
    }

    private static long copyLarge(InputStream input, OutputStream output, byte[] buffer) throws IOException {
        long count = 0L;
        int n;
        if (input != null) {
            while(-1 != (n = input.read(buffer))) {
                output.write(buffer, 0, n);
                count += (long)n;
            }
        }

        return count;
    }

    public static String formatDurationLong(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();
        return  (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hours, ") +
                (minutes == 0 ? "" : minutes + " minutes, ") +
                (seconds == 0 ? "" : seconds + " seconds");
    }

    public static String formatDurationShort(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();

        return  (days == 0 ? "" : days + ":") +
                (hours == 0 ? "" : "%02d:".formatted(hours)) +
                ("%02d:".formatted(minutes)) +
                ("%02d".formatted(seconds));
    }

    public static String formatDurationShortest(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();

        return  (days == 0 ? "" : days + ":") +
                (hours == 0 ? "" : "%d:".formatted(hours)) +
                ((hours > 0 || days > 0) ? "%02d:".formatted(minutes) : "%d:".formatted(minutes)) +
                ("%02d".formatted(seconds));
    }

    // formatBytes is 1024-base
    public static String formatBytes(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.1f %ciB", value / 1024.0, ci.current());
    }

    private static final String[] SI_UNITS = { "B", "kB", "MB", "GB", "TB", "PB", "EB" };
    private static final String[] BINARY_UNITS = { "B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB" };
    public static String humanReadableByteCount(final long bytes, final boolean useSIUnits, final Locale locale)
    {
        final String[] units = useSIUnits ? SI_UNITS : BINARY_UNITS;
        final int base = useSIUnits ? 1000 : 1024;

        // When using the smallest unit no decimal point is needed, because it's the exact number.
        if (bytes < base) {
            return bytes + " " + units[0];
        }

        final int exponent = (int) (Math.log(bytes) / Math.log(base));
        final String unit = units[exponent];
        return String.format(locale, "%.1f %s", bytes / Math.pow(base, exponent), unit);
    }

    // formatBytes SI is 1000-base
    public static String formatBytesSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }

    public static String[] cssClasses(String... clazz) {
        return clazz;
    }

    public static <T extends Widget> T addHover(T row, Runnable onEnter, Runnable onLeave) {
        var ec = EventControllerMotion.builder().setPropagationPhase(PropagationPhase.CAPTURE).setPropagationLimit(PropagationLimit.NONE).build();
        ec.onEnter((x, y) -> {
            onEnter.run();
        });
        ec.onLeave(() -> {
            onLeave.run();
        });
        row.addController(ec);
        return row;
    }

    public static boolean withinEpsilon(double value1, double value2, double epsilon) {
        var diff = Math.abs(value1 - value2);
        return diff < epsilon;
    }

    public static Box.Builder<? extends Box.Builder> borderBox(Orientation orientation, int margins) {
        return Box.builder()
                .setOrientation(orientation)
                .setHexpand(true)
                .setVexpand(true)
                .setMarginStart(margins)
                .setMarginTop(margins)
                .setMarginEnd(margins)
                .setMarginBottom(margins);
    }
}
