package dev.friendai.features.mining.excavation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ExcavationGoalTest {
    @Test
    void countIsACapAndItemIsAnExactGoal() {
        ExcavationGoal count = new ExcavationGoal(4, null, 0);
        assertFalse(count.itemGoal());
        assertTrue(count.countReached(4));
        assertTrue(count.reached(4, Map.of()));

        ExcavationGoal item = new ExcavationGoal(100, "minecraft:raw_iron", 3);
        assertTrue(item.itemGoal());
        assertFalse(item.itemReached(Map.of("minecraft:raw_iron", 2)));
        assertTrue(item.itemReached(Map.of("minecraft:raw_iron", 3)));
        assertEquals(3, item.taken(Map.of("minecraft:raw_iron", 3)));
    }

    @Test
    void specClampsCountAndRequiresAConsistentUntilPair() {
        ExcavationGeometry box = ExcavationGeometry.box(0, 0, 0, 2, 1, 1).orElseThrow();

        ExcavationSpec spec =
                ExcavationSpec.of(box, 0, null, 0, true, false, false, true, 0).orElseThrow();
        assertEquals(box.volume(), spec.count());
        assertFalse(spec.hasUntil());

        assertTrue(ExcavationSpec.of(box, 1, "minecraft:raw_iron", 0, true, false, false, true, 0)
                .isEmpty());
        assertTrue(ExcavationSpec.of(box, 1, "minecraft:raw_iron", 3, false, false, false, true, 0)
                .isEmpty());
        assertTrue(
                ExcavationSpec.of(box, 1, null, 0, true, false, false, true, 96).isEmpty());
    }
}
