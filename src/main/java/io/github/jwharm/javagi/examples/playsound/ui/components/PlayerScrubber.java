package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import io.github.jwharm.javagi.gobject.SignalConnection;
import org.gnome.gobject.GObject;
import org.gnome.gtk.*;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        currentTimeLabel = Label.builder()
                .setLabel(LABEL_ZERO)
                .setCssClasses(Utils.cssClasses("dim-label", "numeric"))
                .build();
        endTimeLabel = Label.builder()
                .setLabel(LABEL_ZERO)
                .setCssClasses(Utils.cssClasses("dim-label", "numeric"))
                .build();

        scale = disableScroll(new Scale(Orientation.HORIZONTAL, Adjustment.builder().build()));
        scale.setSizeRequest(400, 24);
        scale.setHalign(Align.FILL);
        scale.setDrawValue(false);
        scale.setShowFillLevel(true);
        scale.setRestrictToFillLevel(false);

        final Range.ValueChangedCallback onPressedCallback = () -> {
            Duration temporaryPosition = Duration.ofSeconds((long) scale.getValue());
            currentTimeLabel.setLabel(Utils.formatDurationShortest(temporaryPosition));
        };

        GestureClick gestureClick = findGestureClick(scale);
        final var signalRef = new AtomicReference<SignalConnection<Range.ValueChangedCallback>>(null);
        gestureClick.onPressed((nPress, x, y) -> {
            isPressed.set(true);
            //System.out.println("onPressed");
            SignalConnection<Range.ValueChangedCallback> connection = scale.onValueChanged(onPressedCallback);
            signalRef.set(connection);
        });
        gestureClick.onStopped(() -> {
            //System.out.println("onStopped");
        });
        gestureClick.onReleased((a, b, c) -> {
            //System.out.println("onReleased");
            try {
                var signal = signalRef.get();
                if (signal != null) {
                    signal.disconnect();
                    signalRef.set(null);
                }
                Duration finalPosition = Duration.ofSeconds((long) scale.getValue());
                currentTimeLabel.setLabel(Utils.formatDurationShortest(finalPosition));
                this.onPositionSeeked(finalPosition);
            } finally {
                isPressed.set(false);
            }
        });

        this.append(currentTimeLabel);
        this.append(scale);
        this.append(endTimeLabel);
    }

    // This is a workaround for the bugged release event when using a GestureClick in:
    // Find the built-in GestureClick and modify that one.
    // https://gitlab.gnome.org/GNOME/gtk/-/issues/4939
    // https://stackoverflow.com/questions/72303475/gtk4-gestureclick-no-released-signal-emitted
    private GestureClick findGestureClick(Scale scale) {
        var list = scale.observeControllers();
        int nItems = list.getNItems();
        for (int i = 0; i < nItems; i++) {
            GObject item = list.getItem(i);
            if (item instanceof GestureClick) {
                return (GestureClick) item;
            }
        }
        throw new IllegalStateException("no native GestureClick found in Gtk.Scale");
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
        var text = Utils.formatDurationShortest(currentTime);
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
        System.out.printf("PlayerBar.newDuration: %dsec\n", totalTime.getSeconds());
        endTime = totalTime;
        var endTimeText = Utils.formatDurationShortest(totalTime);
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
        ec.onScroll((double dx, double dy) -> true);
        scale.addController(ec);
        return scale;
    }

    private final AtomicInteger counter = new AtomicInteger();
    public void setFill(long total, long count) {
        int i = counter.addAndGet(1);
        if (i % 25 != 0) {
            if (total != count) {
                return;
            }
        }
        this.scale.setShowFillLevel(true);
        //this.scale.setRestrictToFillLevel(true);
        double fill = (double) count / (double) total;
        double fillLevel = endTime.toSeconds() * fill;
        this.scale.setFillLevel(fillLevel);
        // changing fill level does not always redraw the scale component,
        // but scale.getAdjustment().emitValueChanged() forces a redraw:
        this.scale.getAdjustment().emitValueChanged();
        System.out.printf("fill: %.2f level=%.1f\n", fill, fillLevel);
    }

    public void disableFill() {
        this.scale.setFillLevel(1.0);
        this.scale.setRestrictToFillLevel(false);
        this.scale.setShowFillLevel(false);
        this.scale.getAdjustment().emitValueChanged();
    }
}
