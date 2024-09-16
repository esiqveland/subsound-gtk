package io.github.jwharm.javagi.examples.playsound.utils.javahttp;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;

import java.util.Locale;

public class TextUtils {

    public static ServerClient.Biography parseLink(String biography) {
        int index = biography.indexOf("<a ");
        if (index < 0) {
            return new ServerClient.Biography(biography, biography, "");
        }
        var linkString = biography.substring(index, biography.length())
                //.replaceAll("\"", "\\\\\"")
                .replaceAll("target='_blank'", "")
                .replaceAll("rel=\"nofollow\"", "")
                .replaceAll("rel=\\\\\"nofollow\\\\\"", "");
        var cleaned = biography.substring(0, index).trim();
        return new ServerClient.Biography(biography, cleaned, linkString);
    }

    public static String padLeft(String inputString, int length) {
        return String.format("%1$" + length + "s", inputString);
    }

    public static String capitalize(String s) {
        if (s == null) {
            return "";
        }
        var first = s.substring(0, 1).toUpperCase(Locale.ENGLISH);
        if (s.length() <= 1) {
            return first;
        }
        var lower = s.toLowerCase(Locale.ENGLISH).substring(1);
        return first + lower;
    }
}

