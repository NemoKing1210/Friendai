package dev.friendai.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.pathing.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class StanceOrderTest {
    private static final Pos TARGET = new Pos(0, 64, 0);
    private static final Pos FEET = new Pos(0, 64, 0);

    @Test
    void theNearestSameHeightCellComesFirst() {
        List<Pos> candidates = StanceOrder.candidates(TARGET, FEET, 3);
        assertEquals(new Pos(0, 64, -1), candidates.getFirst());
        assertEquals(new Pos(1, 64, 0), candidates.get(1));
    }

    @Test
    void theTargetColumnIsNeverACandidate() {
        List<Pos> candidates = StanceOrder.candidates(TARGET, FEET, 3);
        assertFalse(candidates.contains(TARGET), candidates.toString());
        assertTrue(candidates.stream().noneMatch(p -> p.x() == 0 && p.z() == 0 && p.y() == 64));
    }

    @Test
    void candidatesAreUniqueAndDeterministic() {
        List<Pos> first = StanceOrder.candidates(TARGET, FEET, 3);
        assertEquals(first, StanceOrder.candidates(TARGET, FEET, 3));
        assertEquals(first.size(), first.stream().distinct().count());
    }

    @Test
    void aLargerRadiusOnlyAppendsRings() {
        List<Pos> one = StanceOrder.candidates(TARGET, FEET, 1);
        List<Pos> two = StanceOrder.candidates(TARGET, FEET, 2);
        assertEquals(one, two.subList(0, one.size()));
        assertTrue(two.size() > one.size());
        assertEquals(8 * 7, one.size(), "one ring at seven height offsets and eight compass directions");
    }

    @Test
    void aStepUpIsTriedBeforeAStepDown() {
        List<Pos> candidates = StanceOrder.candidates(TARGET, FEET, 1);
        int level = candidates.indexOf(new Pos(0, 64, -1));
        int up = candidates.indexOf(new Pos(0, 65, -1));
        int down = candidates.indexOf(new Pos(0, 63, -1));
        assertTrue(level < up && up < down, "order was " + candidates);
    }

    @Test
    void aZeroRadiusHasNoStance() {
        assertTrue(StanceOrder.candidates(TARGET, FEET, 0).isEmpty());
    }
}
