package com.github.subsound.utils;

public class OsUtil {
    public enum OS {
        WINDOWS, LINUX, MACOS, SOLARIS
    }
    private static final OS os = findOS();

    public static OS getOSPlatform() {
        return os;
    }

    private static OS findOS() {
        String operSys = System.getProperty("os.name").toLowerCase();
        if (operSys.contains("win")) {
            return OS.WINDOWS;
        } else if (operSys.contains("nix") || operSys.contains("nux") || operSys.contains("aix")) {
            return OS.LINUX;
        } else if (operSys.contains("mac")) {
            return OS.MACOS;
        } else if (operSys.contains("sunos")) {
            return OS.SOLARIS;
        }
        return OS.LINUX;
    }
}
