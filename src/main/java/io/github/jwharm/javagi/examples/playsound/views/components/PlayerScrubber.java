package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.*;

import java.time.Duration;

public class PlayerScrubber extends Box {
    private final Scale scale;
    private final Label currentTimeLabel;
    private final Label endTimeLabel;
    private Duration endTime = Duration.ZERO;

    public PlayerScrubber() {
        super(Orientation.HORIZONTAL, 5);

        scale = new Scale(Orientation.HORIZONTAL, Adjustment.builder().build());
        scale.setSizeRequest(400, 24);
        scale.setDrawValue(false);
        scale.setRestrictToFillLevel(false);

        currentTimeLabel = Label.builder().setLabel("-:--").build();
        endTimeLabel = Label.builder().setLabel("-:--").build();

        this.append(currentTimeLabel);
        this.append(scale);
        this.append(endTimeLabel);
    }

    public void updateDuration(Duration totalTime) {
        if (endTime.equals(totalTime)) {
            return;
        }
        endTime = totalTime;
        var endTime = Utils.formatDurationShort(totalTime);
        Utils.runOnMainThread(() -> {
            this.endTimeLabel.setLabel(endTime);
        });
    }
}
