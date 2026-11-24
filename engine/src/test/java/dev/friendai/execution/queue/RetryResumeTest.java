package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RetryResumeTest {
    @Test
    void savedProgressIsResumedRatherThanRepeated() {
        assertEquals(RetryResume.Action.RESUME, RetryResume.decide(true, false, true));
        assertEquals(RetryResume.Action.RESUME, RetryResume.decide(true, true, true));
        assertEquals(RetryResume.Action.RESUME, RetryResume.decide(true, true, false));
    }

    @Test
    void aCleanFailureWithoutCheckpointStartsOver() {
        assertEquals(RetryResume.Action.RESTART, RetryResume.decide(false, false, true));
        assertEquals(RetryResume.Action.RESTART, RetryResume.decide(false, false, false));
    }

    @Test
    void aCrashMayHaveChangedTheWorldBeforeItRecordedAnything() {
        assertEquals(RetryResume.Action.ASK, RetryResume.decide(false, true, true));
        assertEquals(RetryResume.Action.RESTART, RetryResume.decide(false, true, false));
    }

    @Test
    void unreadableProgressNeverRepeatsACommittingTool() {
        assertEquals(RetryResume.Action.ASK, RetryResume.unreadable(true));
        assertEquals(RetryResume.Action.RESTART, RetryResume.unreadable(false));
    }
}
