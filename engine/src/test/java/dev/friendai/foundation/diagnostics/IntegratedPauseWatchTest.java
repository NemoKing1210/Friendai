package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntegratedPauseWatchTest {
    @BeforeEach
    void reset() {
        IntegratedPauseWatch.resetForTests();
    }

    @Test
    void noPauseMeansNoPausedTime() {
        IntegratedPauseWatch.observe(false, 1_000);
        IntegratedPauseWatch.observe(false, 9_000);
        assertEquals(0L, IntegratedPauseWatch.pausedMillisAt(9_000));
    }

    @Test
    void anOpenPauseCountsUpToTheQuestion() {
        IntegratedPauseWatch.observe(true, 1_000);
        assertEquals(4_000L, IntegratedPauseWatch.pausedMillisAt(5_000));
        assertEquals(9_000L, IntegratedPauseWatch.pausedMillisAt(10_000), "an unclosed pause keeps counting");
    }

    @Test
    void closingAPauseKeepsTheSameTotal() {
        IntegratedPauseWatch.observe(true, 1_000);
        IntegratedPauseWatch.observe(false, 5_000);
        assertEquals(4_000L, IntegratedPauseWatch.pausedMillisAt(5_000));
        assertEquals(4_000L, IntegratedPauseWatch.pausedMillisAt(9_000), "a closed pause no longer grows");
    }

    @Test
    void pausesAccumulate() {
        IntegratedPauseWatch.observe(true, 1_000);
        IntegratedPauseWatch.observe(false, 3_000);
        IntegratedPauseWatch.observe(true, 4_000);
        IntegratedPauseWatch.observe(false, 5_500);
        assertEquals(3_500L, IntegratedPauseWatch.pausedMillisAt(20_000));
    }

    @Test
    void aRepeatedMenuStateDoesNotRestartTheInterval() {
        IntegratedPauseWatch.observe(true, 1_000);
        IntegratedPauseWatch.observe(true, 2_000);
        IntegratedPauseWatch.observe(false, 4_000);
        assertEquals(3_000L, IntegratedPauseWatch.pausedMillisAt(4_000));
    }
}
