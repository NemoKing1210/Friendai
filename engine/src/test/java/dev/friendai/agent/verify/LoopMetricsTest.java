package dev.friendai.agent.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoopMetricsTest {
    private static final String EMPTY = "loop: 0 verified, 0 unverified goals; 0 completions refused; "
            + "0 duplicate refusals; 0 interrupts (max 0 ticks, avg 0); 0 repeated remedies; 0 clarifications";

    @Test
    void aFreshWindowNamesEveryCounterAtZero() {
        assertEquals(EMPTY, new LoopMetrics().summary());
    }

    @Test
    void everyCounterAppearsOnceAndInOrder() {
        LoopMetrics metrics = new LoopMetrics();
        metrics.goalVerified();
        metrics.goalUnverified();
        metrics.completionRefused();
        metrics.duplicateWorkRefused();
        metrics.remedyRepeated();
        metrics.clarificationAsked();

        assertEquals(
                "loop: 1 verified, 1 unverified goals; 1 completions refused; 1 duplicate refusals; "
                        + "0 interrupts (max 0 ticks, avg 0); 1 repeated remedies; 1 clarifications",
                metrics.summary());
    }

    @Test
    void interruptsTrackCountWithLongestAndTruncatedAverage() {
        LoopMetrics metrics = new LoopMetrics();
        metrics.interruptIssued();
        metrics.interruptCleared(40);
        metrics.interruptIssued();
        metrics.interruptCleared(81);
        metrics.interruptIssued();
        metrics.interruptCleared(60);

        // (40 + 81 + 60) / 3 = 60 and one third, truncated to 60.
        assertEquals(
                "loop: 0 verified, 0 unverified goals; 0 completions refused; 0 duplicate refusals; "
                        + "3 interrupts (max 81 ticks, avg 60); 0 repeated remedies; 0 clarifications",
                metrics.summary());
    }

    @Test
    void issuedInterruptsWithoutAClearKeepZeroMaxAndAverage() {
        LoopMetrics metrics = new LoopMetrics();
        metrics.interruptIssued();
        metrics.interruptIssued();

        assertEquals(
                "loop: 0 verified, 0 unverified goals; 0 completions refused; 0 duplicate refusals; "
                        + "2 interrupts (max 0 ticks, avg 0); 0 repeated remedies; 0 clarifications",
                metrics.summary());
    }

    @Test
    void negativeWaitCountsAsZeroRatherThanLoweringTheAverage() {
        LoopMetrics metrics = new LoopMetrics();
        metrics.interruptIssued();
        metrics.interruptCleared(-5);
        metrics.interruptIssued();
        metrics.interruptCleared(10);

        assertEquals(
                "loop: 0 verified, 0 unverified goals; 0 completions refused; 0 duplicate refusals; "
                        + "2 interrupts (max 10 ticks, avg 5); 0 repeated remedies; 0 clarifications",
                metrics.summary());
    }

    @Test
    void resetReturnsEveryCounterToZero() {
        LoopMetrics metrics = new LoopMetrics();
        metrics.goalVerified();
        metrics.goalUnverified();
        metrics.completionRefused();
        metrics.duplicateWorkRefused();
        metrics.interruptIssued();
        metrics.interruptCleared(120);
        metrics.remedyRepeated();
        metrics.clarificationAsked();

        metrics.reset();

        assertEquals(EMPTY, metrics.summary());
    }
}
