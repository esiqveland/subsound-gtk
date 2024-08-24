package io.github.jwharm.javagi.examples.playsound.utils.javahttp;

import io.github.jwharm.javagi.examples.playsound.integration.ServerClient;

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
}
