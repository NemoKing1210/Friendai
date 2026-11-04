package dev.friendai.features.construction.clear;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.construction.blueprint.ClearKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClearOutcomeTest {
    @Test
    void lavaFlowingWaterAndAnExcludedSourceAreLeftByDesignNotFailed() {
        ClearOutcome outcome = ClearOutcome.of(
                Map.of(),
                Map.of(
                        ClearSkip.LAVA, 1,
                        ClearSkip.FLOWING, 2,
                        ClearSkip.NOT_WANTED, 1),
                0,
                0);

        assertEquals(4, outcome.leftByDesignTotal());
        assertEquals(0, outcome.failedTotal());
        assertFalse(outcome.failedRun());
        assertFalse(outcome.nothingToDo());
    }

    @Test
    void aCellThatChangedBeforeTheBreakIsLeftByDesign() {
        ClearOutcome outcome = ClearOutcome.of(Map.of(), Map.of(ClearSkip.CHANGED, 3), 0, 0);

        assertFalse(outcome.failedRun());
        assertEquals(3, outcome.leftByDesignTotal());
        assertEquals(0, outcome.failedTotal());
    }

    @Test
    void theBlockUnderTheFeetIsLeftByDesignNotFailed() {
        ClearOutcome outcome = ClearOutcome.of(Map.of(), Map.of(ClearSkip.UNDER_FEET, 1), 0, 0);

        assertEquals(0, outcome.failedTotal());
        assertEquals(1, outcome.leftByDesignTotal());
        assertFalse(outcome.failedRun());
        assertFalse(outcome.nothingToDo());
    }

    @Test
    void attemptsThatDidNotWorkAreAFailureWhenNothingWasCleared() {
        ClearOutcome outcome = ClearOutcome.of(
                Map.of(),
                Map.of(
                        ClearSkip.UNREACHABLE, 2,
                        ClearSkip.PROTECTED, 1),
                0,
                0);

        assertTrue(outcome.failedRun());
        assertEquals(3, outcome.failedTotal());
    }

    @Test
    void theSameAttemptsAreNotAFailureOnceOneCellWasCleared() {
        ClearOutcome outcome = ClearOutcome.of(Map.of(ClearKind.VEGETATION, 1), Map.of(ClearSkip.UNREACHABLE, 2), 0, 0);

        assertFalse(outcome.failedRun());
        assertEquals(1, outcome.clearedTotal());
    }

    @Test
    void anEmptyAreaHasNothingToDo() {
        assertTrue(ClearOutcome.of(Map.of(), Map.of(), 0, 0).nothingToDo());
    }

    @Test
    void solidsAndTheNextPassAreNotNothingToDo() {
        assertFalse(ClearOutcome.of(Map.of(), Map.of(), 3, 0).nothingToDo());
        assertFalse(ClearOutcome.of(Map.of(), Map.of(), 0, 5).nothingToDo());

        ClearOutcome outcome = ClearOutcome.of(Map.of(ClearKind.SNOW, 1, ClearKind.VEGETATION, 2), Map.of(), 0, 0);
        assertEquals(List.of(ClearKind.VEGETATION, ClearKind.SNOW), outcome.clearedKinds());
    }
}
