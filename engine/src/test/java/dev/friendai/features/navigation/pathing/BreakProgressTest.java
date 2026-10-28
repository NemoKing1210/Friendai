package dev.friendai.features.navigation.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BreakProgressTest {
    @Test
    void instantBlocksTakeOneTick() {
        assertEquals(1, BreakProgress.ticksToBreak(0f, 1f, true));
    }

    @Test
    void correctToolUsesThirty() {
        assertEquals(60, BreakProgress.ticksToBreak(2f, 1f, true));
    }

    @Test
    void fasterToolShortensTime() {
        assertEquals(30, BreakProgress.ticksToBreak(2f, 2f, true));
    }

    @Test
    void wrongToolUsesHundred() {
        assertEquals(150, BreakProgress.ticksToBreak(1.5f, 1f, false));
    }
}
