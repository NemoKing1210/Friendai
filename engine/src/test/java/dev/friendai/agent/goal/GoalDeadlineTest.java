package dev.friendai.agent.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GoalDeadlineTest {
    private static final long NOW = 10_000;

    @Test
    void noDeadlineHasNoPhrase() {
        assertEquals("", GoalDeadline.describe(0, NOW));
        assertEquals("", GoalDeadline.describe(-5, NOW));
    }

    @Test
    void aDeadlineAheadReadsAsDueInWholeMinutes() {
        assertEquals("due in 12 minutes", GoalDeadline.describe(NOW + 12 * GoalDeadline.TICKS_PER_MINUTE, NOW));
        assertEquals("due in 1 minutes", GoalDeadline.describe(NOW + 1, NOW), "a part minute still counts as one");
    }

    @Test
    void aPassedDeadlineReadsAsOverdueInWholeMinutes() {
        assertEquals("overdue by 3 minutes", GoalDeadline.describe(NOW, NOW + 3 * GoalDeadline.TICKS_PER_MINUTE));
        assertEquals("overdue by 1 minutes", GoalDeadline.describe(NOW, NOW), "the due tick is already late");
        assertEquals(
                "overdue by 2 minutes",
                GoalDeadline.describe(NOW, NOW + 2 * GoalDeadline.TICKS_PER_MINUTE),
                "whole minutes stay exact");
        assertEquals(
                "overdue by 3 minutes",
                GoalDeadline.describe(NOW, NOW + 2 * GoalDeadline.TICKS_PER_MINUTE + 1),
                "a part minute rounds away from zero");
    }

    @Test
    void minutesBecomeAbsoluteTicksAndZeroStaysNoDeadline() {
        assertEquals(NOW + 1_440 * GoalDeadline.TICKS_PER_MINUTE, GoalDeadline.tickFrom(NOW, 1_440));
        assertEquals(0L, GoalDeadline.tickFrom(NOW, 0));
        assertEquals(0L, GoalDeadline.tickFrom(NOW, -10));
    }
}
