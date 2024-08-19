package io.github.jwharm.javagi.examples.playsound.ui.components;

import io.github.jwharm.javagi.examples.playsound.utils.Utils;
import org.gnome.gtk.Button;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.PropagationPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.addHover;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class StarButton extends Button {
    private static final Logger log = LoggerFactory.getLogger(StarButton.class);
    private Optional<Instant> starredAt;
    private final Function<Boolean, CompletableFuture<Void>> onChanged;
    private final GestureClick gestureClick;

    public StarButton(Optional<Instant> starredAt, Function<Boolean, CompletableFuture<Void>> onChanged) {
        super();
        this.starredAt = starredAt;
        this.onChanged = onChanged;
        gestureClick = GestureClick.builder().setPropagationPhase(PropagationPhase.CAPTURE).build();
        gestureClick.onStopped(() -> {
            log.info("StarredButton.onStopped");
            //this.onClick.run();
        });
        gestureClick.onReleased((int nPress, double x, double y) -> {
            log.info("StarredButton.onReleased nPress={}", nPress);
            var oldValue = this.starredAt;
            Optional<Instant> newValue = oldValue.isPresent() ? Optional.empty() : Optional.of(Instant.now());
            this.setStarredAt(newValue);

            this.onChanged.apply(newValue.isPresent()).whenComplete((a, ex) -> {
                if (ex == null) {
                    return;
                } else {
                    this.setStarredAt(oldValue);
                }
            });
        });
        addHover(
                this,
                () -> this.setLabel(getIcon(!this.starredAt.isPresent())),
                () -> this.setLabel(getIcon(this.starredAt.isPresent()))
        );
        this.addController(gestureClick);
        this.setLabel(getIcon(starredAt));
        this.setCssClasses(cssClasses("starred", "flat", "circular"));
    }

    public void setStarredAt(Optional<Instant> starredAt) {
        this.starredAt = starredAt;
        Utils.runOnMainThread(() -> this.setLabel(getIcon(starredAt)));
    }

    private static String getIcon(Optional<Instant> starredAt) {
        return getIcon(starredAt.isPresent());
    }
    private static String getIcon(boolean isStarred) {
        return isStarred ? "★" : "☆";
    }
}
