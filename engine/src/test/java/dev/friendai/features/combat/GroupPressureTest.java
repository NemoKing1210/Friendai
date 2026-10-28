package dev.friendai.features.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GroupPressureTest {

    @Test
    void stepIsPerpendicularToTheTargetAndAwayFromTheSecondThreat() {
        GroupPressure.Step step = GroupPressure.sidestep(0, 0, 0, 5, 2, 0);
        assertEquals(-1.0, step.dx(), 1e-9);
        assertEquals(0.0, step.dz(), 1e-9);

        GroupPressure.Step otherSide = GroupPressure.sidestep(0, 0, 5, 0, 0, 2);
        assertEquals(0.0, otherSide.dx(), 1e-9);
        assertEquals(-1.0, otherSide.dz(), 1e-9);
    }

    @Test
    void stepLengthIsOne() {
        GroupPressure.Step step = GroupPressure.sidestep(3, 7, -4, 11, 9, -2);
        assertEquals(1.0, Math.hypot(step.dx(), step.dz()), 1e-9);
    }

    @Test
    void aSecondThreatOnTheLineKeepsTheDeterministicSide() {
        GroupPressure.Step step = GroupPressure.sidestep(0, 0, 0, 5, 0, 3);
        assertEquals(-1.0, step.dx(), 1e-9);
        assertEquals(0.0, step.dz(), 1e-9);
    }

    @Test
    void overlappingTargetFallsBackToPositiveX() {
        GroupPressure.Step step = GroupPressure.sidestep(4, 4, 4, 4, 6, 4);
        assertEquals(1.0, step.dx(), 1e-9);
        assertEquals(0.0, step.dz(), 1e-9);
        assertTrue(Math.hypot(step.dx(), step.dz()) == 1.0);
    }
}
