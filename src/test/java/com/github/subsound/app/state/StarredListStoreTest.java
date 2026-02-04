package com.github.subsound.app.state;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class StarredListStoreTest {

    private static ArrayList<String> ids(String... ids) {
        return new ArrayList<>(List.of(ids));
    }

    /**
     * Simulates applying the diff to a mutable list, mirroring how
     * refreshAsync applies removals (backwards) and insertions (forwards)
     * to the ListStore.
     */
    private static List<String> applyDiff(List<String> current, List<String> newIds, StarredListStore.IndexDiff diff) {
        var result = new ArrayList<>(current);
        // Remove backwards to preserve indices
        var removals = diff.removalIndices();
        for (int i = removals.size() - 1; i >= 0; i--) {
            result.remove((int) removals.get(i));
        }
        // Insert forwards at computed positions
        for (var ins : diff.insertions()) {
            result.add(ins.position(), newIds.get(ins.position()));
        }
        return result;
    }

    private static void assertDiffProduces(List<String> current, List<String> newIds) {
        var diff = StarredListStore.computeDiff(current, newIds);
        var result = applyDiff(current, newIds, diff);
        assertThat(result).containsExactlyElementsOf(newIds);
    }

    @Test
    public void emptyToEmpty() {
        assertDiffProduces(ids(), ids());
    }

    @Test
    public void firstLoad() {
        assertDiffProduces(ids(), ids("A", "B", "C"));
    }

    @Test
    public void noChanges() {
        assertDiffProduces(ids("A", "B", "C"), ids("A", "B", "C"));
    }

    @Test
    public void removeAll() {
        assertDiffProduces(ids("A", "B", "C"), ids());
    }

    @Test
    public void removeFromMiddle() {
        assertDiffProduces(ids("A", "B", "C", "D", "E"), ids("A", "C", "E"));
    }

    @Test
    public void insertAtFront() {
        assertDiffProduces(ids("B", "C"), ids("A", "B", "C"));
    }

    @Test
    public void insertAtEnd() {
        assertDiffProduces(ids("A", "B"), ids("A", "B", "C"));
    }

    @Test
    public void insertInMiddle() {
        assertDiffProduces(ids("A", "C"), ids("A", "B", "C"));
    }

    @Test
    public void mixedRemovalsAndInsertions() {
        assertDiffProduces(
                ids("A", "B", "C", "D", "E"),
                ids("F", "A", "C", "G", "E")
        );
    }

    @Test
    public void completeReplacement() {
        assertDiffProduces(ids("A", "B", "C"), ids("D", "E", "F"));
    }

    @Test
    public void multipleInsertionsAtFront() {
        assertDiffProduces(ids("C"), ids("A", "B", "C"));
    }

    @Test
    public void noChangesProducesNoOps() {
        var diff = StarredListStore.computeDiff(ids("A", "B", "C"), ids("A", "B", "C"));
        assertThat(diff.removalIndices()).isEmpty();
        assertThat(diff.insertions()).isEmpty();
    }
}