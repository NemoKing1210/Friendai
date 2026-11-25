package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClockGapPolicyTest {
    @Test
    void aSlowTickKeepsItsReason() {
        assertEquals("slow-tick", ClockGapPolicy.reason(1_500, 1_450, 0));
    }

    @Test
    void aShortTickDoesNotExplainTheGap() {
        assertEquals("unknown", ClockGapPolicy.reason(1_500, 100, 0));
    }

    @Test
    void aPauseMenuCoveringTheGapNamesThePause() {
        assertEquals("paused", ClockGapPolicy.reason(14_350, 0, 14_350));
        assertEquals("paused", ClockGapPolicy.reason(14_350, 0, 14_300), "the existing slack still applies");
    }

    @Test
    void aWallClockJumpStaysUnknown() {
        assertEquals("unknown", ClockGapPolicy.reason(14_350, 0, 0));
    }

    @Test
    void anUnexplainedGapStaysUnknown() {
        assertEquals("unknown", ClockGapPolicy.reason(14_350, 0, 2_000));
    }

    @Test
    void theTickThatExplainsTheGapWinsOverAPause() {
        assertEquals("slow-tick", ClockGapPolicy.reason(1_500, 1_450, 1_450));
    }
}
