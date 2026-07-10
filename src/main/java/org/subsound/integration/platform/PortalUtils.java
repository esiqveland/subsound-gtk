package org.subsound.integration.platform;

import org.gnome.glib.GLib;

// https://docs.flatpak.org/en/latest/portals.html
public class PortalUtils {

    // Use g_get_user_config_dir(), g_get_user_cache_dir() and g_get_user_data_dir() to
    // find the right place to store configuration and data
    public static String getUserConfigDir() {
        var dir = GLib.getUserConfigDir();
        if (dir == null) {
            return null;
        }
        return dir.toString();
    }
    public static String getUserDataDir() {
        var dir = GLib.getUserDataDir();
        if (dir == null) {
            return null;
        }
        return dir.toString();
    }
    public static String getUserCacheDir() {
        var dir = GLib.getUserCacheDir();
        if (dir == null) {
            return null;
        }
        return dir.toString();
    }
}
