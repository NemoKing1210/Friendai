package dev.friendai.features.navigation.movement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PacePolicyTest {

    @Test
    void neverRunsWhenUnableToSprint() {
        for (PaceMode mode : PaceMode.values()) {
            assertFalse(PacePolicy.shouldRun(mode, true, 50.0, false, true), mode.name());
        }
    }

    @Test
    void runModeRunsWhenAble() {
        assertTrue(PacePolicy.shouldRun(PaceMode.RUN, false, 0.0, true, false));
    }

    @Test
    void walkModeNeverRuns() {
        assertFalse(PacePolicy.shouldRun(PaceMode.WALK, true, 50.0, true, true));
    }

    @Test
    void autoDoesNotRunWhenNotFollowing() {
        assertFalse(PacePolicy.shouldRun(PaceMode.AUTO, false, 50.0, true, false));
        assertFalse(PacePolicy.shouldRun(PaceMode.AUTO, false, 50.0, true, true));
    }

    @Test
    void autoStartsRunningBeyondStartDistance() {
        assertFalse(PacePolicy.shouldRun(PaceMode.AUTO, true, 9.9, true, false));
        assertTrue(PacePolicy.shouldRun(PaceMode.AUTO, true, 10.1, true, false));
    }

    @Test
    void autoKeepsRunningUntilStopDistance() {
        assertTrue(PacePolicy.shouldRun(PaceMode.AUTO, true, 7.0, true, true));
        assertFalse(PacePolicy.shouldRun(PaceMode.AUTO, true, 4.9, true, true));
    }

    @Test
    void autoPathRunsLongLegsWithHysteresis() {
        assertFalse(PacePolicy.shouldRunOnPath(PaceMode.AUTO, 9.9, true, false));
        assertTrue(PacePolicy.shouldRunOnPath(PaceMode.AUTO, 10.1, true, false));
        assertTrue(PacePolicy.shouldRunOnPath(PaceMode.AUTO, 7.0, true, true));
        assertFalse(PacePolicy.shouldRunOnPath(PaceMode.AUTO, 4.9, true, true));
    }

    @Test
    void explicitModesKeepTheirMeaningOnAPath() {
        assertFalse(PacePolicy.shouldRunOnPath(PaceMode.WALK, 50.0, true, true));
        assertTrue(PacePolicy.shouldRunOnPath(PaceMode.RUN, 0.0, true, false));
        assertFalse(PacePolicy.shouldRunOnPath(PaceMode.RUN, 50.0, false, false));
    }
}
