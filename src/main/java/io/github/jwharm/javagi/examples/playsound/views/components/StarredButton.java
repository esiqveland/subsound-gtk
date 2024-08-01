package io.github.jwharm.javagi.examples.playsound.views.components;

import org.gnome.gtk.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

import static io.github.jwharm.javagi.examples.playsound.utils.Utils.addHover;
import static io.github.jwharm.javagi.examples.playsound.utils.Utils.cssClasses;

public class StarredButton extends Label {
    private static final Logger log = LoggerFactory.getLogger(StarredButton.class);
    private Optional<Instant> starredAt;
    private final Consumer<Boolean> onChanged;

    public StarredButton(Optional<Instant> starredAt, Consumer<Boolean> onChanged) {
        super("");
        this.starredAt = starredAt;
        this.onChanged = onChanged;
        GestureClick clicks = GestureClick.builder().setPropagationPhase(PropagationPhase.CAPTURE).build();
        clicks.onStopped(() -> {
            log.info("StarredButton.onStopped");
            //this.onClick.run();
        });
        clicks.onPressed((int nPress, double x, double y) -> {
            log.info("StarredButton.onPressed nPress={}", nPress);
            //this.onClick.run();
        });
        clicks.onReleased((int nPress, double x, double y) -> {
            log.info("StarredButton.onReleased nPress={}", nPress);
            var newValue = !starredAt.isPresent();
            this.onChanged.accept(newValue);
            this.setLabel(getIcon(newValue));
        });
        addHover(
                this,
                () -> this.setLabel(getIcon(!starredAt.isPresent())),
                () -> this.setLabel(getIcon(starredAt.isPresent()))
        );
        this.addController(clicks);
        this.setLabel(getIcon(starredAt));
        this.setCssClasses(cssClasses("starred"));
    }

    public void setStarredAt(Optional<Instant> starredAt) {
        this.starredAt = starredAt;
        this.setLabel(getIcon(starredAt));
    }

    private static String getIcon(Optional<Instant> starredAt) {
        return getIcon(starredAt.isPresent());
    }
    private static String getIcon(boolean isStarred) {
        return isStarred ? "★" : "☆";
    }
}
