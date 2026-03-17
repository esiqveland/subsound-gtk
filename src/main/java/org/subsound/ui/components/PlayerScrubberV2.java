package org.subsound.ui.components;

import org.gnome.gdk.RGBA;
import org.gnome.gdk.PaintableFlags;
import org.gnome.gobject.GObject;
import org.gnome.graphene.Rect;
import org.gnome.gsk.RoundedRect;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.GestureDrag;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Picture;
import org.subsound.utils.Utils;

import java.lang.foreign.Arena;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PlayerScrubberV2 extends GObject implements org.gnome.gdk.Paintable {

    private double position = 0.0;
    private double fill = 0.0;

    @Override
    public void snapshot(org.gnome.gdk.Snapshot gdkSnapshot, double width, double height) {
        try (var arena = Arena.ofConfined()) {
            var snapshot = (org.gnome.gtk.Snapshot) gdkSnapshot;

            float trackHeight = 3.0f;
            float trackY = (float) ((height - trackHeight) / 2.0);

            // Track background
            appendRoundedRect(arena, snapshot, 0, trackY, (float) width, trackHeight, 1.0f,
                    new RGBA(0.24f, 0.24f, 0.24f, 1.0f));

            // Fill / buffer indicator
            if (fill > 0.0) {
                float fillWidth = (float) (fill * width);
                appendRoundedRect(arena, snapshot, 0, trackY, fillWidth, trackHeight, 1.0f,
                        new RGBA(0.35f, 0.35f, 0.35f, 1.0f));
            }

            // Progress
            float progressWidth = (float) (position * width);
            if (progressWidth > 0) {
                appendRoundedRect(arena, snapshot, 0, trackY, progressWidth, trackHeight, 1.0f,
                        new RGBA(0.21f, 0.52f, 0.90f, 1.0f));
            }
        }
    }

    private static void appendRoundedRect(
            Arena arena,
            org.gnome.gtk.Snapshot snapshot,
            float x,
            float y,
            float w,
            float h,
            float radius,
            RGBA color
    ) {
        var rect = new Rect(arena).init(x, y, w, h);
        var rr = new RoundedRect(arena);
        rr.initFromRect(rect, radius);
        snapshot.pushRoundedClip(rr);
        snapshot.appendColor(color, rect);
        snapshot.pop();
    }

    @Override
    public Set<PaintableFlags> getFlags() {
        return Set.of();
    }

    void setPosition(double normalized) {
        position = normalized;
        invalidateContents();
    }

    void setFill(double normalized) {
        fill = normalized;
        invalidateContents();
    }

    // -------------------------------------------------------------------------
    // ScrubberWidget — GTK widget wrapping this paintable
    // -------------------------------------------------------------------------
    public static class ScrubberWidget extends Box {
        private static final String LABEL_ZERO = "-:--";

        private final PlayerScrubberV2 paintable;
        private final Label currentTimeLabel;
        private final Label endTimeLabel;
        private final Picture picture;
        private final Consumer<Duration> onPositionChanged;

        private Duration endTime = Duration.ZERO;
        private Duration currentPosition = Duration.ZERO;
        private double endTimeSecs = 0.0;

        private final AtomicBoolean isDragging = new AtomicBoolean(false);
        private double dragPosition = 0.0;
        private double startX = 0.0;

        public ScrubberWidget(Consumer<Duration> onPositionChanged) {
            super(Orientation.HORIZONTAL, 5);
            this.onPositionChanged = onPositionChanged;
            this.paintable = new PlayerScrubberV2();

            currentTimeLabel = Label.builder()
                    .setLabel(LABEL_ZERO)
                    .setWidthChars(5)
                    .setMaxWidthChars(5)
                    .setValign(Align.CENTER)
                    .setCssClasses(Utils.cssClasses("dim-label", "numeric", "caption"))
                    .build();
            endTimeLabel = Label.builder()
                    .setLabel(LABEL_ZERO)
                    .setWidthChars(5)
                    .setMaxWidthChars(5)
                    .setValign(Align.CENTER)
                    .setCssClasses(Utils.cssClasses("dim-label", "numeric", "caption"))
                    .build();

            picture = Picture.forPaintable(paintable);
            picture.setContentFit(ContentFit.FILL);
            picture.setValign(Align.CENTER);
            picture.setSizeRequest(400, 20);

            setupGestures();

            this.append(currentTimeLabel);
            this.append(picture);
            this.append(endTimeLabel);
        }

        private void setupGestures() {
            GestureClick gestureClick = new GestureClick();
            gestureClick.onPressed((nPress, x, y) -> {
                isDragging.set(true);
                dragPosition = clamp(x / picture.getWidth(), 0.0, 1.0);
                paintable.setPosition(dragPosition);
                currentTimeLabel.setLabel(Utils.formatDurationShortest(toAbsoluteDuration(dragPosition)));
            });
            gestureClick.onReleased((nPress, x, y) -> {
                if (isDragging.getAndSet(false)) {
                    var finalDuration = toAbsoluteDuration(dragPosition);
                    currentTimeLabel.setLabel(Utils.formatDurationShortest(finalDuration));
                    onPositionChanged.accept(finalDuration);
                }
            });
            picture.addController(gestureClick);

            GestureDrag gestureDrag = new GestureDrag();
            gestureDrag.onDragBegin((x, y) -> {
                startX = x;
            });
            gestureDrag.onDragUpdate((dx, dy) -> {
                dragPosition = clamp((startX + dx) / picture.getWidth(), 0.0, 1.0);
                paintable.setPosition(dragPosition);
                currentTimeLabel.setLabel(Utils.formatDurationShortest(toAbsoluteDuration(dragPosition)));
            });
            picture.addController(gestureDrag);
        }

        private Duration toAbsoluteDuration(double normalizedPosition) {
            return Duration.ofSeconds((long) (normalizedPosition * endTimeSecs));
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }

        public void updatePosition(Duration currentTime) {
            currentTime = Duration.ofSeconds(currentTime.toSeconds());
            if (currentPosition.equals(currentTime)) {
                return;
            }
            if (isDragging.get()) {
                return;
            }
            currentPosition = currentTime;
            var text = Utils.formatDurationShortest(currentTime);
            double normalized = endTimeSecs > 0 ? (double) currentTime.toSeconds() / endTimeSecs : 0.0;
            double pos = normalized;
            Utils.runOnMainThread(() -> {
                paintable.setPosition(pos);
                currentTimeLabel.setLabel(text);
            });
        }

        public void updateDuration(Duration totalTime) {
            if (totalTime.isZero()) {
                endTime = totalTime;
                endTimeSecs = 0.0;
                Utils.runOnMainThread(() -> {
                    currentTimeLabel.setLabel(LABEL_ZERO);
                    endTimeLabel.setLabel(LABEL_ZERO);
                    paintable.setPosition(0.0);
                });
                return;
            }

            totalTime = totalTime.plusMillis(500);
            totalTime = Duration.ofSeconds(totalTime.toSeconds());
            if (endTime.equals(totalTime)) {
                return;
            }
            endTime = totalTime;
            endTimeSecs = totalTime.toSeconds();
            var endTimeText = Utils.formatDurationShortest(totalTime);
            Utils.runOnMainThread(() -> {
                endTimeLabel.setLabel(endTimeText);
            });
        }

        public void setFill(long total, long count) {
            double fill = total > 0 ? (double) count / (double) total : 0.0;
            paintable.setFill(fill);
        }

        public void disableFill() {
            paintable.setFill(0.0);
        }
    }
}
