package com.github.subsound.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import org.apache.commons.codec.Resources;
import org.apache.commons.io.IOUtils;
import org.gnome.glib.GLib;
import org.gnome.glib.MainContext;
import org.gnome.glib.SourceFunc;
import org.gnome.glib.SourceOnceFunc;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PropagationLimit;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Widget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
    public static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.STRICT).create();


    public static <T> CompletableFuture<T> doAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ASYNC_EXECUTOR);
    }

    public static CompletableFuture<Void> doAsync(Runnable supplier) {
        return CompletableFuture.runAsync(supplier, ASYNC_EXECUTOR);
    }

    public static void runOnMainThread(SourceOnceFunc fn) {
        // Consider switching to:
        //MainContext.default_().invoke(fn);
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

    public static String formatDurationMedium(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();
        return  (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hr, ") +
                (minutes == 0 ? "" : minutes + " min, ") +
                (seconds == 0 ? "" : seconds + " sec");
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

    public static <T extends Widget> T addClick(T row, Runnable onClick) {
        var gestureClick = GestureClick.builder().build();
        row.addController(gestureClick);
        gestureClick.onReleased((int nPress, double x, double y) -> {
            //System.out.println("addClick.gestureClick.onReleased: " + nPress);
            onClick.run();
        });

        return row;
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

    public static <T extends Widget> T addHover2(T row, Runnable onEnter, Runnable onLeave) {
        var ec = EventControllerMotion.builder().setPropagationPhase(PropagationPhase.CAPTURE).setPropagationLimit(PropagationLimit.NONE).build();
        var enterCallbackSignalConnection = ec.onEnter((x, y) -> {
            onEnter.run();
        });
        var leaveSignal = ec.onLeave(() -> {
            onLeave.run();
        });
        row.addController(ec);
        row.onDestroy(() -> {
            enterCallbackSignalConnection.disconnect();
            leaveSignal.disconnect();
        });
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

    public static Label.Builder<? extends Label.Builder> heading1(String labelText) {
        return Label.builder().setLabel(labelText).setHalign(Align.START).setCssClasses(cssClasses("title-3"));
    }

    public static <T> T fromJson(String s, Class<T> clazz) {
        return GSON.fromJson(s, clazz);
    }
    public static <T> String toJson(T obj) {
        return GSON.toJson(obj);
    }

    public static @Nullable String firstNotNull(String ...ss) {
        for (String s : ss) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    public static @NotNull String firstNotBlank(String ...ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    public static String mustRead(Path cssFile) {
        try {
            var relPath = cssFile.toString();
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readString(Path.of(localFilePath), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] mustReadBytes(String resourcesFilePath) {
        try {
            var relPath = resourcesFilePath;
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readAllBytes(Path.of(localFilePath));
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                URL resource = Utils.class.getResource(relPath);
                if (resource != null) {
                    Path path = Path.of(resource.toURI());
                    return mustReadBytes(path);
                }
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toByteArray(inputStream);
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] mustReadBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

//    public static File getResourceDirectory(String resource) {
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        URL res = classLoader.getResource(resource);
//        File fileDirectory;
//        if ("jar".equals(res.getProtocol())) {
//            InputStream input = classLoader.getResourceAsStream(resource);
//            List<String> fileNames = IOUtils.readLines(input, StandardCharsets.UTF_8);
//            fileNames.forEach(name -> {
//                String fileResourceName = resource + File.separator + name;
//                File tempFile = new File(fileDirectory.getPath() + File.pathSeparator + name);
//                InputStream fileInput = classLoader.getResourceAsStream(resourceFileName);
//                FileUtils.copyInputStreamToFile(fileInput, tempFile);
//            });
//            fileDirectory.deleteOnExit();
//        } else {
//            fileDirectory = new File(res.getFile());
//        }
//
//        return fileDirectory;
//    }
}
