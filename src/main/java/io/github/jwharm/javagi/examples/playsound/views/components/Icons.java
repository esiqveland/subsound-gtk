package io.github.jwharm.javagi.examples.playsound.views.components;

public enum Icons {
    PLAY("media-playback-start-symbolic"),
    PAUSE("media-playback-pause-symbolic");

    private final String iconName;
    Icons(String iconName) {
        this.iconName = iconName;
    }

    public String getIconName() {
        return iconName;
    }
}
