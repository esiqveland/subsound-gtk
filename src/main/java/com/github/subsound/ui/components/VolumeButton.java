package com.github.subsound.ui.components;

import org.gnome.glib.GLib;
import org.gnome.gtk.Button;

public class VolumeButton extends Button {
    private boolean isMuted;
    private double currentVolume = -2.0;
    private Icons currentIcon = Icons.VolumeHigh;

    public VolumeButton(boolean isMuted, double volume) {
        this.isMuted = isMuted;
        this.setIconName(currentIcon.getIconName());
        this.setVolume(volume);
    }

    public void setMute(boolean isMuted) {
        this.isMuted = isMuted;
        if (this.isMuted) {
            updateIcon(Icons.VolumeMuted);
        } else {
            setVolume(currentVolume);
        }
    }

    public void setVolume(double nextVolume) {
        currentVolume = nextVolume;
        if (isMuted) {
            updateIcon(Icons.VolumeMuted);
            return;
        }
        if (nextVolume < 0.01) {
            updateIcon(Icons.VolumeMuted);
            return;
        }
        if (nextVolume >= 0.70) {
            updateIcon(Icons.VolumeHigh);
            return;
        }
        if (nextVolume >= 0.50) {
            updateIcon(Icons.VolumeMedium);
            return;
        }
        if (nextVolume > 0.0) {
            updateIcon(Icons.VolumeLow);
            return;
        }
    }

    private void updateIcon(Icons next) {
        if (next != currentIcon) {
            System.out.printf("VolumeIcon: old icon=%s next=%s%n", currentIcon.getIconName(), next.getIconName());
            currentIcon = next;
            GLib.idleAddOnce(() -> {
                this.setIconName(next.getIconName());
            });
        }
    }
}
