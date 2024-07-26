package io.github.jwharm.javagi.examples.playsound.views.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gobject.SignalConnection;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class PlayerScrubber extends Box {
    private static final String LABEL_ZERO = "-:--";

    private final Consumer<Duration> onPositionChanged;
    private final Scale scale;
    private final Label currentTimeLabel;
    private final Label endTimeLabel;
    private Duration endTime = Duration.ZERO;
    private Duration currentPosition = Duration.ZERO;
    private final AtomicBoolean isPressed = new AtomicBoolean(false);

    public PlayerScrubber(Consumer<Duration> onPositionChanged) {
        super(Orientation.HORIZONTAL, 5);
        this.onPositionChanged = onPositionChanged;

        currentTimeLabel = Label.builder().setLabel(LABEL_ZERO).build();
        endTimeLabel = Label.builder().setLabel(LABEL_ZERO).build();

        GestureClick gesture = new GestureClick();
        GestureDrag gestureDrag = new GestureDrag();
        scale = disableScroll(new Scale(Orientation.HORIZONTAL, Adjustment.builder().build()));
        scale.setSizeRequest(400, 24);
        scale.setHalign(Align.FILL);
        scale.setDrawValue(false);
        scale.setRestrictToFillLevel(false);

        final Range.ValueChangedCallback onPressedCallback = () -> {
            Duration temporaryPosition = Duration.ofSeconds((long) scale.getValue());
            currentTimeLabel.setLabel(Utils.formatDurationShort(temporaryPosition));
        };
        {
            final var signalRef = new AtomicReference<SignalConnection<Range.ValueChangedCallback>>(null);
            gesture.onPressed((nPress, x, y) -> {
                isPressed.set(true);
                System.out.println("onPressed");
                SignalConnection<Range.ValueChangedCallback> connection = scale.onValueChanged(onPressedCallback);
                signalRef.set(connection);
            });
            gesture.onReleased((a, b, c) -> {
                System.out.println("onReleased");
            });
            gesture.onStopped(() -> {
                try {
                    System.out.println("onStopped");
                    var signal = signalRef.get();
                    if (signal != null) {
                        signal.disconnect();
                        signalRef.set(null);
                    }
                    Duration finalPosition = Duration.ofSeconds((long) scale.getValue());
                    currentTimeLabel.setLabel(Utils.formatDurationShort(finalPosition));
                    this.onPositionSeeked(finalPosition);
                } finally {
                    isPressed.set(false);
                }
            });
        }

        {
            final var signalRef = new AtomicReference<SignalConnection<Range.ValueChangedCallback>>(null);
            gestureDrag.onDragBegin((double offsetX, double offsetY) -> {
                isPressed.set(true);
                System.out.println("onDragBegin");
                SignalConnection<Range.ValueChangedCallback> connection = scale.onValueChanged(onPressedCallback);
                signalRef.set(connection);
            });
            gestureDrag.onDragEnd((double offsetX, double offsetY) -> {
                try {
                    System.out.println("onDragEnd");
                    var signal = signalRef.get();
                    if (signal != null) {
                        signal.disconnect();
                        signalRef.set(null);
                    }
                    Duration finalPosition = Duration.ofSeconds((long) scale.getValue());
                    currentTimeLabel.setLabel(Utils.formatDurationShort(finalPosition));
                    this.onPositionSeeked(finalPosition);
                } finally {
                    isPressed.set(false);
                }
            });
        }
        scale.addController(gesture);
        scale.addController(gestureDrag);

        this.append(currentTimeLabel);
        this.append(scale);
        this.append(endTimeLabel);
    }

    private void onPositionSeeked(Duration finalPosition) {
        this.onPositionChanged.accept(finalPosition);
    }

    public void updatePosition(Duration currentTime) {
        // normalize to seconds:
        currentTime = Duration.ofSeconds(currentTime.toSeconds());
        if (currentPosition.equals(currentTime)) {
            return;
        }
        // ignore position updates while we are scrubbing
        if (isPressed.get()) {
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

    // disable scrollwheel on scale:
    // https://stackoverflow.com/a/77268812
    private static Scale disableScroll(Scale scale) {
        EventControllerScroll ec = EventControllerScroll.builder()
                .setPropagationPhase(PropagationPhase.CAPTURE)
                .setFlags(Set.of(EventControllerScrollFlags.BOTH_AXES))
                .build();
        ec.onScroll((double dx, double dy) -> {
            return true;
        });
        scale.addController(ec);
        return scale;
    }
}
