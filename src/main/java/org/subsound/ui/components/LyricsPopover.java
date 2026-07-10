package org.subsound.ui.components;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerScroll;
import org.gnome.gtk.EventControllerScrollFlags;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StackTransitionType;
import org.gnome.gtk.Viewport;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.gnome.graphene.Rect;
import org.gnome.pango.WrapMode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.lyrics.LyricsResult;
import org.subsound.utils.Utils;

import java.lang.foreign.Arena;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import static org.subsound.i18n.I18n.tr;

/**
 * Popover showing lyrics for the currently playing song.
 * Synced lyrics highlight and auto-scroll to the current line as the song plays,
 * and clicking a line seeks to its timestamp. Plain lyrics are display-only.
 * The lyrics source is abstracted behind the provider function, so it works with
 * any backend (OpenSubsonic songLyrics, lrclib.net, ...).
 */
public class LyricsPopover extends Popover {
    private final Logger log = LoggerFactory.getLogger(LyricsPopover.class);

    private static final String PAGE_LOADING = "loading";
    private static final String PAGE_EMPTY = "empty";
    private static final String PAGE_LYRICS = "lyrics";
    // after the user scrolls manually, leave the viewport alone for a while:
    private static final Duration USER_SCROLL_GRACE = Duration.ofSeconds(4);

    private final Function<SongInfo, Optional<LyricsResult>> lyricsProvider;
    private final Consumer<Duration> onSeek;
    // future-valued so concurrent opens dedupe to one request per song:
    private final Cache<String, CompletableFuture<Optional<LyricsResult>>> cache = Caffeine.newBuilder()
            .maximumSize(100)
            .build();

    private final Stack stack;
    private final ScrolledWindow scrolled;
    private final Box linesBox;
    private final Label emptyLabel;

    // Row state below is only touched on the GTK main thread.
    private final List<Label> lineWidgets = new ArrayList<>();
    // timestamps parallel to lineWidgets; stays empty for plain lyrics => no highlight/seek
    private final List<Long> lineTimesMs = new ArrayList<>();
    private int activeIndex = -1;
    // which song the built rows belong to; null when cleared
    private @Nullable String loadedSongId = null;
    private Instant userScrolledAt = Instant.EPOCH;

    private volatile long lastPositionMs = 0;
    private volatile Optional<SongInfo> currentSong = Optional.empty();

    public LyricsPopover(Function<SongInfo, Optional<LyricsResult>> lyricsProvider, Consumer<Duration> onSeek) {
        super();
        this.lyricsProvider = lyricsProvider;
        this.onSeek = onSeek;

        this.linesBox = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(6)
                .setMarginTop(12)
                .setMarginBottom(12)
                .setMarginStart(12)
                .setMarginEnd(12)
                .build();

        // set the Viewport explicitly so ScrolledWindow.getChild() isn't an implicit wrapper:
        var viewport = Viewport.builder()
                .setChild(linesBox)
                .setScrollToFocus(false)
                .build();
        this.scrolled = ScrolledWindow.builder()
                .setChild(viewport)
                .setMinContentHeight(300)
                .setMaxContentHeight(800)
                .setPropagateNaturalHeight(true)
                .setPropagateNaturalWidth(true)
                .setMinContentWidth(380)
                .setMaxContentWidth(380)
                .setHscrollbarPolicy(PolicyType.NEVER)
                .build();

        var scrollController = EventControllerScroll.builder()
                .setFlags(Set.of(EventControllerScrollFlags.VERTICAL))
                .build();
        scrollController.onScroll((double dx, double dy) -> {
            this.userScrolledAt = Instant.now();
            return false;
        });
        this.scrolled.addController(scrollController);

        this.emptyLabel = Label.builder()
                .setLabel(tr("No lyrics found"))
                .setMarginTop(16)
                .setMarginBottom(16)
                .setMarginStart(16)
                .setMarginEnd(16)
                .build();
        this.emptyLabel.addCssClass(Classes.labelDim.className());

        this.stack = Stack.builder()
                .setVhomogeneous(false)
                .setHhomogeneous(true)
                .setTransitionType(StackTransitionType.CROSSFADE)
                .build();
        this.stack.addNamed(new LoadingSpinner(), PAGE_LOADING);
        this.stack.addNamed(emptyLabel, PAGE_EMPTY);
        this.stack.addNamed(scrolled, PAGE_LYRICS);

        var header = Label.builder()
                .setLabel(tr("Lyrics"))
                .setMarginTop(8)
                .setMarginBottom(8)
                .build();
        header.addCssClass(Classes.heading.className());

        var content = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(4)
                .build();
        content.append(header);
        content.append(stack);

        this.setChild(content);
        this.setPosition(PositionType.TOP);

        this.onShow(() -> {
            updateMaxHeight();
            loadForCurrentSong();
        });
    }

    /**
     * Called from AppManager state changes (virtual thread). Diffs by song id and
     * clears stale lyrics; refetches immediately only while the popover is open.
     */
    public void setNowPlaying(Optional<SongInfo> song) {
        var newId = song.map(SongInfo::id).orElse(null);
        var oldId = this.currentSong.map(SongInfo::id).orElse(null);
        this.currentSong = song;
        if (Objects.equals(newId, oldId)) {
            return;
        }
        Utils.runOnMainThread(() -> {
            clearLines();
            if (this.getMapped()) {
                loadForCurrentSong();
            }
        });
    }

    /**
     * Called on the GTK main thread (PlayerBar tick and position snaps).
     * Cheap no-op while the popover is closed or the lyrics are unsynced.
     */
    public void updatePosition(Duration position) {
        this.lastPositionMs = position.toMillis();
        if (!this.getMapped() || lineTimesMs.isEmpty()) {
            return;
        }
        applyHighlight(this.lastPositionMs, false);
    }

    private void loadForCurrentSong() {
        var songOpt = this.currentSong;
        if (songOpt.isEmpty()) {
            emptyLabel.setLabel(tr("Nothing playing"));
            stack.setVisibleChildName(PAGE_EMPTY);
            return;
        }
        var song = songOpt.get();
        if (song.id().equals(this.loadedSongId)) {
            // rows already built for this song: re-sync highlight after the popover has a size
            Utils.runOnMainThread(() -> applyHighlight(this.lastPositionMs, true));
            return;
        }
        stack.setVisibleChildName(PAGE_LOADING);
        var future = cache.get(song.id(), id -> Utils.doAsync(() -> lyricsProvider.apply(song)));
        future.whenComplete((result, err) -> {
            if (!Optional.of(song.id()).equals(this.currentSong.map(SongInfo::id))) {
                // stale: song changed while fetching
                return;
            }
            if (err != null) {
                log.warn("failed to fetch lyrics for songId={}: {}", song.id(), err.getMessage());
                // allow a retry the next time the popover opens:
                cache.invalidate(song.id());
            }
            var res = err != null ? Optional.<LyricsResult>empty() : result;
            Utils.runOnMainThread(() -> showResult(song.id(), res));
        });
    }

    private void showResult(String songId, Optional<LyricsResult> result) {
        if (!Optional.of(songId).equals(this.currentSong.map(SongInfo::id))) {
            return;
        }
        clearLines();
        this.loadedSongId = songId;
        switch (result.orElse(null)) {
            case LyricsResult.SyncedLyrics synced -> {
                for (var line : synced.lines()) {
                    long timeMs = line.timeMs();
                    var label = new ClickLabel(line.text(), () -> this.onSeek.accept(Duration.ofMillis(timeMs)));
                    configureLine(label);
                    lineWidgets.add(label);
                    lineTimesMs.add(timeMs);
                    linesBox.append(label);
                }
                stack.setVisibleChildName(PAGE_LYRICS);
                // rows need an allocation pass before scroll math works; defer one main-loop iteration
                Utils.runOnMainThread(() -> applyHighlight(this.lastPositionMs, true));
            }
            case LyricsResult.PlainLyrics plain -> {
                for (var text : plain.lines()) {
                    var label = new Label(text);
                    configureLine(label);
                    lineWidgets.add(label);
                    linesBox.append(label);
                }
                stack.setVisibleChildName(PAGE_LYRICS);
            }
            case null -> {
                emptyLabel.setLabel(tr("No lyrics found"));
                stack.setVisibleChildName(PAGE_EMPTY);
            }
        }
    }

    private void configureLine(Label label) {
        label.setWrap(true);
        // With hscrollbar-policy NEVER the ScrolledWindow ignores max-content-width and
        // propagates the child's natural width — and a wrapping Label still reports its
        // full single-line text as natural width. Clamp the natural width on the label
        // itself so long lines wrap instead of widening the popover.
        label.setMaxWidthChars(40);
        // break inside overlong words too, so a single word can't force the width up:
        label.setWrapMode(WrapMode.WORD_CHAR);
        label.setJustify(Justification.CENTER);
        label.setHalign(Align.CENTER);
        label.addCssClass(Classes.lyricsLine.className());
    }

    private void clearLines() {
        Widget child = linesBox.getFirstChild();
        while (child != null) {
            var next = child.getNextSibling();
            linesBox.remove(child);
            child = next;
        }
        lineWidgets.clear();
        lineTimesMs.clear();
        activeIndex = -1;
        loadedSongId = null;
    }

    private void applyHighlight(long posMs, boolean forceScroll) {
        if (lineTimesMs.isEmpty()) {
            return;
        }
        int idx = findActiveIndex(posMs);
        if (idx == activeIndex && !forceScroll) {
            return;
        }
        if (activeIndex >= 0 && activeIndex < lineWidgets.size() && activeIndex != idx) {
            lineWidgets.get(activeIndex).removeCssClass(Classes.lyricsLineActive.className());
        }
        activeIndex = idx;
        if (idx >= 0) {
            var widget = lineWidgets.get(idx);
            widget.addCssClass(Classes.lyricsLineActive.className());
            scrollToLine(widget);
        }
    }

    // last line with timeMs <= posMs, or -1 before the first line
    private int findActiveIndex(long posMs) {
        int lo = 0;
        int hi = lineTimesMs.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lineTimesMs.get(mid) <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private void scrollToLine(Widget row) {
        if (!this.getMapped()) {
            return;
        }
        if (Duration.between(userScrolledAt, Instant.now()).compareTo(USER_SCROLL_GRACE) < 0) {
            return;
        }
        try (var arena = Arena.ofConfined()) {
            var rect = new Rect(arena);
            if (!row.computeBounds(linesBox, rect)) {
                return;
            }
            // center the active line in the viewport:
            var adj = scrolled.getVadjustment();
            double target = rect.getY() + rect.getHeight() / 2.0 - adj.getPageSize() / 2.0;
            adj.setValue(Math.max(adj.getLower(), Math.min(target, adj.getUpper() - adj.getPageSize())));
        }
    }

    private void updateMaxHeight() {
        var root = this.getRoot();
        if (root instanceof Window window) {
            int windowHeight = window.getHeight();
            if (windowHeight > 0) {
                int maxHeight = Math.max(400, windowHeight - 150);
                scrolled.setMaxContentHeight(maxHeight);
            }
        }
    }
}
