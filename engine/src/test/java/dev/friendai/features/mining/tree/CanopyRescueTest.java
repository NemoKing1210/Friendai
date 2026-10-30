package dev.friendai.features.mining.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CanopyRescueTest {
    private static CanopyRescue.Cells leaves(Set<TreeCell> cells) {
        return (x, y, z) -> cells.contains(new TreeCell(x, y, z));
    }

    @Test
    void picksTheLeafTheDropRestsOn() {
        Set<TreeCell> leaf = Set.of(new TreeCell(2, 5, 2));
        List<TreeCell> targets = CanopyRescue.targets(List.of(new TreeCell(2, 5, 2)), leaves(leaf), 8);
        assertEquals(List.of(new TreeCell(2, 5, 2)), targets);
    }

    @Test
    void ignoresADropRestingOnAnythingElse() {
        List<TreeCell> targets = CanopyRescue.targets(
                List.of(new TreeCell(2, 5, 2), new TreeCell(4, 5, 4)), leaves(Set.of(new TreeCell(2, 5, 2))), 8);
        assertEquals(List.of(new TreeCell(2, 5, 2)), targets, "a drop on ground or a log is not rescued: " + targets);
    }

    @Test
    void aRepeatedSupportGivesOneTarget() {
        List<TreeCell> targets = CanopyRescue.targets(
                List.of(new TreeCell(2, 5, 2), new TreeCell(2, 5, 2)), leaves(Set.of(new TreeCell(2, 5, 2))), 8);
        assertEquals(List.of(new TreeCell(2, 5, 2)), targets, "deduplicated, not repeated");
    }

    @Test
    void capBoundsTheTargets() {
        List<TreeCell> supports = List.of(new TreeCell(0, 5, 0), new TreeCell(2, 5, 0), new TreeCell(4, 5, 0));
        Set<TreeCell> leaf = Set.of(new TreeCell(0, 5, 0), new TreeCell(2, 5, 0), new TreeCell(4, 5, 0));
        List<TreeCell> targets = CanopyRescue.targets(supports, leaves(leaf), 2);
        assertEquals(2, targets.size(), "capped");
        assertTrue(targets.containsAll(List.of(new TreeCell(0, 5, 0), new TreeCell(2, 5, 0))), "nearest first");
    }
}
