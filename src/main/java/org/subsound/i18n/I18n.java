package org.subsound.i18n;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.javagi.util.Intl;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.constants.Constants;

/**
 * Gettext-based translation support.
 *
 * <p>Call {@link #init()} once at the very top of {@code main()}, before any UI strings are
 * created. Then statically import {@link #tr}, {@link #trn} and {@link #trc} and wrap all
 * user-visible string literals. Server-provided data (song titles, artist names, etc.) must
 * never be wrapped.
 *
 * <p>Translations degrade gracefully to English: if the gettext library, locale directory or
 * message catalog is missing, the original msgid is returned unchanged.
 */
public final class I18n {
    private static final Logger log = LoggerFactory.getLogger(I18n.class);
    private static final String DOMAIN = Constants.APP_ID;
    // glibc value; the setlocale/bind_textdomain_codeset downcalls are Linux-only.
    private static final int LC_ALL_GLIBC = 6;

    private I18n() {
    }

    public static void init() {
        // gettext resolves the language from the C locale. GTK only calls setlocale(LC_ALL, "")
        // later inside gtk_init, so do it here to cover strings created before app.run().
        if (isLinux()) {
            setlocale();
        }
        String localeDir = resolveLocaleDir();
        if (localeDir != null) {
            // Intl.bindtextdomain permanently disables translations if the dir does not exist,
            // so resolveLocaleDir only ever returns existing directories.
            Intl.bindtextdomain(DOMAIN, localeDir);
            if (isLinux()) {
                bindTextdomainCodeset(DOMAIN, "UTF-8");
            }
            log.info("i18n: bound text domain {} to {}", DOMAIN, localeDir);
        } else {
            log.info("i18n: no locale dir found, using system default search path");
        }
        Intl.textdomain(DOMAIN);
    }

    /** Translate a message. */
    public static String tr(String msgid) {
        return Intl.i18n(msgid);
    }

    /** Translate a message with singular/plural forms selected by n. */
    public static String trn(String msgid, String msgidPlural, int n) {
        return Intl.i18n(msgid, msgidPlural, n);
    }

    /** Translate a message with singular/plural forms selected by n. */
    public static String trn(String msgid, String msgidPlural, long n) {
        return Intl.i18n(msgid, msgidPlural, (int) Math.min(n, Integer.MAX_VALUE));
    }

    /** Translate a message with a disambiguating context. */
    public static String trc(String context, String msgid) {
        return Intl.i18n(context, msgid);
    }

    private static @Nullable String resolveLocaleDir() {
        String prop = System.getProperty("subsound.localedir");
        if (prop != null && Files.isDirectory(Path.of(prop))) {
            return prop;
        }
        String env = System.getenv("SUBSOUND_LOCALEDIR");
        if (env != null && Files.isDirectory(Path.of(env))) {
            return env;
        }
        // Flatpak install location:
        if (Files.isDirectory(Path.of("/app/share/locale"))) {
            return "/app/share/locale";
        }
        // Distro package location (also the libc default, but be explicit):
        if (Files.isDirectory(Path.of("/usr/share/locale"))) {
            return "/usr/share/locale";
        }
        return null;
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("linux");
    }

    private static void setlocale() {
        try {
            Linker linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("setlocale");
            if (symbol.isEmpty()) {
                return;
            }
            MethodHandle handle = linker.downcallHandle(symbol.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                var ignored = (MemorySegment) handle.invokeExact(LC_ALL_GLIBC, arena.allocateFrom(""));
            }
        } catch (Throwable t) {
            log.warn("i18n: setlocale failed", t);
        }
    }

    // Intl does not expose bind_textdomain_codeset. Without it, gettext returns strings in the
    // locale codeset instead of guaranteed UTF-8, which GLib and GTK require for app domains.
    private static void bindTextdomainCodeset(String domain, String codeset) {
        try {
            Linker linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("bind_textdomain_codeset");
            if (symbol.isEmpty()) {
                return;
            }
            MethodHandle handle = linker.downcallHandle(symbol.get(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                var ignored = (MemorySegment) handle.invokeExact(
                        arena.allocateFrom(domain), arena.allocateFrom(codeset));
            }
        } catch (Throwable t) {
            log.warn("i18n: bind_textdomain_codeset unavailable", t);
        }
    }
}
