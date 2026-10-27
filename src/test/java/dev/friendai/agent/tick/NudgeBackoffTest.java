package dev.friendai.agent.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NudgeBackoffTest {
    @Test
    void theIntervalDoublesWithEachUnansweredNudgeUpToACap() {
        assertEquals(30, NudgeBackoff.intervalSec(30, 0));
        assertEquals(60, NudgeBackoff.intervalSec(30, 1));
        assertEquals(120, NudgeBackoff.intervalSec(30, 2));
        assertEquals(480, NudgeBackoff.intervalSec(30, 4));
        assertEquals(480, NudgeBackoff.intervalSec(30, 5), "the doubling stops at sixteen times the base");
    }

    @Test
    void nudgesStopAfterAFewWithoutAnyProgress() {
        assertFalse(NudgeBackoff.exhausted(NudgeBackoff.MAX_STREAK - 1));
        assertTrue(NudgeBackoff.exhausted(NudgeBackoff.MAX_STREAK));
    }

    @Test
    void aHugeBaseIsCappedAtAnHour() {
        assertEquals(3600, NudgeBackoff.intervalSec(600, 4));
    }
}
