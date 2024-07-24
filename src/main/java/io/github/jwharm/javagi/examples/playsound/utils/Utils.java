package io.github.jwharm.javagi.examples.playsound.utils;

import org.gnome.glib.GLib;
import org.gnome.glib.SourceOnceFunc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class Utils {
    private static final HexFormat HEX = HexFormat.of().withLowerCase();

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

}
