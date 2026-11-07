package dev.friendai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressWatchTest {
    @Test
    void anUnknownStampCannotBeJudged() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(ProgressWatch.UNKNOWN, -1, 0, 1000, 20));
    }

    @Test
    void aMovingStampResetsTheStallClock() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(1, -1, 0, 10, 20));
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(2, -1, 0, 25, 20));
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(2, -1, 0, 30, 20));
        assertEquals(ProgressWatch.Status.STALLED, watch.observe(2, -1, 0, 45, 20));
    }

    @Test
    void aRebasedClockDoesNotCountTimeTheTaskCouldNotRun() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(1, -1, 0, 100, 40));
        assertEquals(ProgressWatch.Status.STALLED, watch.observe(1, -1, 0, 150, 40), "without a rebase the gap stalls");
        watch.rebase(50);
        assertEquals(
                ProgressWatch.Status.PROGRESS,
                watch.observe(1, -1, 0, 160, 40),
                "a frozen task resumes with its own elapsed time");
        assertEquals(ProgressWatch.Status.STALLED, watch.observe(1, -1, 0, 205, 40));
    }

    @Test
    void aDeclaredWaitUsesItsOwnDeadline() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.WAITING, watch.observe(1, 5, 20, 10, 20));
        assertEquals(ProgressWatch.Status.STALLED, watch.observe(1, 21, 20, 30, 20));
    }

    @Test
    void aDeclaredWaitRestartsTheStallClockWhenItEnds() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(8, -1, 0, 1, 50));
        for (int tick = 2; tick <= 101; tick++) {
            assertEquals(ProgressWatch.Status.WAITING, watch.observe(8, tick, 200, tick, 50), "tick " + tick);
        }
        assertEquals(
                ProgressWatch.Status.PROGRESS,
                watch.observe(8, -1, 0, 102, 50),
                "the first tick after the declared wait must not count the wait as a stall");
        assertEquals(ProgressWatch.Status.PROGRESS, watch.observe(8, -1, 0, 150, 50));
        assertEquals(
                ProgressWatch.Status.STALLED, watch.observe(8, -1, 0, 151, 50), "the ordinary window applies again");
    }

    @Test
    void aWaitWithoutItsOwnDeadlineUsesTheRunnerLimit() {
        ProgressWatch watch = new ProgressWatch(0);
        assertEquals(ProgressWatch.Status.WAITING, watch.observe(1, 100, 0, 100, 20));
    }
}
