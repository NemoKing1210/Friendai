package dev.friendai.agent.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.tick.NudgeBackoff;
import org.junit.jupiter.api.Test;

class ShiftBudgetTest {
    @Test
    void sixPerHourThenBlocked() {
        ShiftBudget budget = new ShiftBudget();
        for (int i = 0; i < 6; i++) {
            assertTrue(budget.allows(i * 1000L, 6));
            budget.record(i * 1000L);
        }
        assertFalse(budget.allows(10_000, 6));
    }

    @Test
    void windowSlides() {
        ShiftBudget budget = new ShiftBudget();
        budget.record(0);
        budget.record(1000);
        assertFalse(budget.allows(2000, 2));
        assertTrue(budget.allows(3_600_001, 2));
    }

    @Test
    void offlineLimitZeroBlocks() {
        assertFalse(new ShiftBudget().allows(0, 0));
    }

    @Test
    void backoffDoublesAndStops() {
        ShiftBudget budget = new ShiftBudget();
        budget.record(0);
        assertTrue(budget.backoffAllows(0, 30));
        budget.failed();
        assertFalse(budget.backoffAllows(59_999, 30));
        assertTrue(budget.backoffAllows(60_000, 30));
        for (int i = 1; i < NudgeBackoff.MAX_STREAK; i++) {
            budget.failed();
        }
        assertFalse(budget.backoffAllows(Long.MAX_VALUE / 2, 30));
        budget.succeeded();
        assertTrue(budget.backoffAllows(60_000, 30));
    }

    @Test
    void ownerSpeakingClearsTheStreak() {
        ShiftBudget budget = new ShiftBudget();
        for (int i = 0; i < NudgeBackoff.MAX_STREAK; i++) {
            budget.failed();
        }
        budget.ownerSpoke();
        assertTrue(budget.backoffAllows(0, 30));
    }
}
