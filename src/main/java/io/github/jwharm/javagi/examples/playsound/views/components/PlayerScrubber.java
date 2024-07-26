package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;

import java.time.Duration;

public class PlayerScrubber extends Box {
    private static final String LABEL_ZERO = "-:--";
    private final Scale scale;
    private final Label currentTimeLabel;
    private final Label endTimeLabel;
    private Duration endTime = Duration.ZERO;
    private Duration currentPosition = Duration.ZERO;

    public PlayerScrubber() {
        super(Orientation.HORIZONTAL, 5);

        scale = new Scale(Orientation.HORIZONTAL, Adjustment.builder().build());
        scale.setSizeRequest(400, 24);
        scale.setHalign(Align.FILL);
        scale.setDrawValue(false);
        scale.setRestrictToFillLevel(false);
        // TODO: disable scrollwheel on scale:
        // https://stackoverflow.com/a/77268812

        currentTimeLabel = Label.builder().setLabel(LABEL_ZERO).build();
        endTimeLabel = Label.builder().setLabel(LABEL_ZERO).build();

        this.append(currentTimeLabel);
        this.append(scale);
        this.append(endTimeLabel);
    }

    public void updatePosition(Duration currentTime) {
        // normalize to seconds:
        currentTime = Duration.ofSeconds(currentTime.toSeconds());
        if (currentPosition.equals(currentTime)) {
            return;
        }
        currentPosition = currentTime;
        var text = Utils.formatDurationShort(currentTime);
        Utils.runOnMainThread(() -> {
            this.scale.setValue(currentPosition.toSeconds());
            this.currentTimeLabel.setLabel(text);
            //this.scale.setRange(0, endTime.toSeconds());
        });
    }

    public void updateDuration(Duration totalTime) {
        if (totalTime.isZero()) {
            endTime = totalTime;
            Utils.runOnMainThread(() -> {
                currentTimeLabel.setLabel(LABEL_ZERO);
                endTimeLabel.setLabel(LABEL_ZERO);
                scale.setRange(0, 1);
            });
            return;
        }
        // avoid rounding down a second to always be certain scale is never shorter than song duration:
        totalTime = totalTime.plusMillis(500);
        totalTime = Duration.ofSeconds(totalTime.toSeconds());
        if (endTime.equals(totalTime)) {
            return;
        }
        endTime = totalTime;
        var endTimeText = Utils.formatDurationShort(totalTime);
        Utils.runOnMainThread(() -> {
            this.endTimeLabel.setLabel(endTimeText);
            this.scale.setRange(0, endTime.toSeconds());
        });
    }
}
