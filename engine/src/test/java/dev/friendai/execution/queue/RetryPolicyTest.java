package dev.friendai.execution.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    private static final RetryPolicy.Last FAILED = new RetryPolicy.Last(7, "give_items", RetryPolicy.Ending.FAILED);
    private static final RetryPolicy.Last SUCCEEDED = new RetryPolicy.Last(7, "stay", RetryPolicy.Ending.SUCCEEDED);
    private static final RetryPolicy.Last PARTIAL = new RetryPolicy.Last(7, "give_items", RetryPolicy.Ending.PARTIAL);
    private static final RetryPolicy.Last REFUSED = new RetryPolicy.Last(7, "give_items", RetryPolicy.Ending.REFUSED);
    private static final RetryPolicy.Last RUNNING = new RetryPolicy.Last(7, "give_items", RetryPolicy.Ending.RUNNING);

    private static RetryPolicy.Outcome decide(RetryPolicy.Last last, long id, boolean live, boolean busy) {
        return RetryPolicy.decide(Optional.of(last), new RetryPolicy.Request(id, "FAILED"), live, busy);
    }

    @Test
    void aSettledFailureIsQueuedAgain() {
        assertEquals(RetryPolicy.Outcome.QUEUED, decide(FAILED, 7, false, false));
    }

    @Test
    void aSuccessIsNeverReplayed() {
        assertEquals(RetryPolicy.Outcome.SUCCEEDED, decide(SUCCEEDED, 7, false, false));
    }

    @Test
    void aJobThatMeasuredPartOfItsWorkIsNotReplayedBlindly() {
        assertEquals(RetryPolicy.Outcome.PARTIAL, decide(PARTIAL, 7, false, false));
    }

    @Test
    void aStepRefusedBeforeItStartedIsNotReplayed() {
        assertEquals(RetryPolicy.Outcome.REFUSED, decide(REFUSED, 7, false, false));
    }

    @Test
    void aStaleClickCannotReachANewerJob() {
        assertEquals(RetryPolicy.Outcome.STALE, decide(FAILED, 6, false, false));
        assertEquals(RetryPolicy.Outcome.STALE, decide(SUCCEEDED, 6, false, false));
    }

    @Test
    void unknownOrBlockingStateIsReportedSeparately() {
        assertEquals(
                RetryPolicy.Outcome.NOTHING,
                RetryPolicy.decide(Optional.empty(), new RetryPolicy.Request(7, "FAILED"), false, false));
        assertEquals(RetryPolicy.Outcome.BUSY, decide(FAILED, 7, false, true));
        assertEquals(RetryPolicy.Outcome.LIVE, decide(FAILED, 7, true, false));
        assertEquals(RetryPolicy.Outcome.LIVE, decide(RUNNING, 7, false, false));
    }

    @Test
    void anUntargetedRequestUsesTheRememberedJob() {
        assertEquals(
                RetryPolicy.Outcome.QUEUED,
                RetryPolicy.decide(Optional.of(FAILED), RetryPolicy.Request.untargeted(), false, false));
    }

    @Test
    void onlyFailedTimedOutAndCancelledHistoryRowsOfferRetry() {
        assertTrue(RetryPolicy.retryableOutcome("FAILED"));
        assertTrue(RetryPolicy.retryableOutcome("timed_out"));
        assertTrue(RetryPolicy.retryableOutcome("CANCELLED"));
        assertFalse(RetryPolicy.retryableOutcome("SUCCESS"));
        assertFalse(RetryPolicy.retryableOutcome(""));
        assertFalse(RetryPolicy.retryableOutcome(null));
    }
}
