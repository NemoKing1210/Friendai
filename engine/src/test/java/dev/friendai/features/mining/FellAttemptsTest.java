package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FellAttemptsTest {
    @Test
    void anAttemptThatGaveLogsResetsTheStreak() {
        assertEquals(0, FellAttempts.nextEmptyStreak(0, 3));
        assertEquals(0, FellAttempts.nextEmptyStreak(1, 1));
    }

    @Test
    void anAttemptThatGaveNothingExtendsTheStreak() {
        assertEquals(1, FellAttempts.nextEmptyStreak(0, 0));
        assertEquals(2, FellAttempts.nextEmptyStreak(1, 0));
    }

    @Test
    void theFallbackStartsOnTheBoundNotOnTheTreeBudget() {
        assertFalse(FellAttempts.fallBack(1, 1, 8, 2));
        assertTrue(FellAttempts.fallBack(2, 2, 8, 2));
    }

    @Test
    void theTreeBudgetStillStopsAWalkThroughManyTrees() {
        assertTrue(FellAttempts.fallBack(8, 0, 8, 2));
        assertFalse(FellAttempts.fallBack(7, 1, 8, 2));
    }
}
