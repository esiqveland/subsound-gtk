package io.github.jwharm.javagi.examples.playsound.integration.platform;

import org.gnome.glib.GLib;

// https://docs.flatpak.org/en/latest/portals.html
public class PortalUtils {

    // Use g_get_user_config_dir(), g_get_user_cache_dir() and g_get_user_data_dir() to
    // find the right place to store configuration and data
    public static String getUserConfigDir() {
        return GLib.getUserConfigDir();
    }
    public static String getUserDataDir() {
        return GLib.getUserDataDir();
    }
    public static String getUserCacheDir() {
        return GLib.getUserCacheDir();
    }
}
