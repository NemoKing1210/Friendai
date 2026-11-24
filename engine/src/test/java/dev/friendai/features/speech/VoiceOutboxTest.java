package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.speech.VoiceOutbox.Abandon;
import dev.friendai.features.speech.VoiceOutbox.Entry;
import dev.friendai.features.speech.VoiceOutbox.Step;
import org.junit.jupiter.api.Test;

final class VoiceOutboxTest {
    private static final String SESSION = "session-a";

    @Test
    void recordingsLeaveInTheOrderTheyWereRecordedEvenWhenTheyFinishInReverse() {
        VoiceOutbox outbox = new VoiceOutbox();
        Entry first = outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();
        Entry second = outbox.reserve(SESSION, "wake_word", 10).orElseThrow();
        Entry third = outbox.reserve(SESSION, "push_to_talk", 20).orElseThrow();

        assertTrue(outbox.settle(third, "third"));
        assertTrue(outbox.settle(second, "second"));
        assertTrue(outbox.settle(first, "first"));

        Step.Send sent = assertInstanceOf(Step.Send.class, outbox.step(33));
        assertSame(first, sent.entry());
        assertEquals("first", sent.entry().text());

        // Nothing else may leave while the head holds the connection.
        assertInstanceOf(Step.Idle.class, outbox.step(34));
        assertSame(first, outbox.sending().orElseThrow());

        outbox.release(first);
        assertEquals(
                "second",
                assertInstanceOf(Step.Send.class, outbox.step(35)).entry().text());
        outbox.release(outbox.sending().orElseThrow());
        assertEquals(
                "third",
                assertInstanceOf(Step.Send.class, outbox.step(36)).entry().text());
        outbox.release(outbox.sending().orElseThrow());
        assertEquals(0, outbox.size());
    }

    @Test
    void aPlaceStillRecognizingHoldsTheOnesBehindIt() {
        VoiceOutbox outbox = new VoiceOutbox();
        Entry first = outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();
        Entry second = outbox.reserve(SESSION, "wake_word", 5).orElseThrow();

        outbox.settle(second, "second");
        Step.Idle idle = assertInstanceOf(Step.Idle.class, outbox.step(7));
        assertEquals(
                VoiceOutbox.RECOGNITION_TIMEOUT_MILLIS,
                idle.deadlineMillis(),
                "the wait is bounded by the unfinished recording");

        outbox.settle(first, "first");
        assertEquals(
                "first",
                assertInstanceOf(Step.Send.class, outbox.step(9)).entry().text());
    }

    @Test
    void aFourthRecordingIsRefusedInsteadOfLostSilently() {
        VoiceOutbox outbox = new VoiceOutbox();
        outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();
        outbox.reserve(SESSION, "push_to_talk", 1).orElseThrow();
        outbox.reserve(SESSION, "push_to_talk", 2).orElseThrow();

        assertTrue(outbox.reserve(SESSION, "push_to_talk", 3).isEmpty());
        assertEquals(VoiceOutbox.MAX_PENDING, outbox.size());
    }

    @Test
    void aCancelledRecordingLeavesNothingBehind() {
        VoiceOutbox outbox = new VoiceOutbox();
        Entry first = outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();
        Entry second = outbox.reserve(SESSION, "wake_word", 1).orElseThrow();

        outbox.cancel(first);
        assertFalse(outbox.settle(first, "late text"), "a cancelled place must not accept text");
        assertTrue(outbox.settle(second, "second"));
        assertEquals(
                "second",
                assertInstanceOf(Step.Send.class, outbox.step(4)).entry().text());
        assertEquals(1, outbox.size());
    }

    @Test
    void aRecognitionThatNeverReportsIsTimedOutAndReported() {
        VoiceOutbox outbox = new VoiceOutbox();
        outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();

        Step.Abandoned abandoned =
                assertInstanceOf(Step.Abandoned.class, outbox.step(VoiceOutbox.RECOGNITION_TIMEOUT_MILLIS));
        assertEquals(Abandon.RECOGNITION_TIMEOUT, abandoned.entries().get(0).reason());
        assertEquals(0, outbox.size());
        assertInstanceOf(Step.Idle.class, outbox.step(VoiceOutbox.RECOGNITION_TIMEOUT_MILLIS + 1));
    }

    @Test
    void anUnansweredHeadIsNotRetried() {
        VoiceOutbox outbox = new VoiceOutbox();
        Entry first = outbox.reserve(SESSION, "push_to_talk", 0).orElseThrow();
        outbox.settle(first, "first");
        assertInstanceOf(Step.Send.class, outbox.step(2));

        Step.Abandoned abandoned =
                assertInstanceOf(Step.Abandoned.class, outbox.step(2 + VoiceOutbox.ADMISSION_TIMEOUT_MILLIS));
        assertEquals(Abandon.ADMISSION_TIMEOUT, abandoned.entries().get(0).reason());
        assertEquals(0, outbox.size(), "a timed-out order is dropped, never sent again");
        assertNull(outbox.sending().orElse(null));
    }

    @Test
    void everyPlaceFromAnEndedConnectionIsDroppedTogether() {
        VoiceOutbox outbox = new VoiceOutbox();
        outbox.reserve("old-session", "push_to_talk", 0).orElseThrow();
        outbox.reserve("old-session", "wake_word", 1).orElseThrow();

        var dropped = outbox.dropSession(SESSION);
        assertEquals(2, dropped.size());
        assertTrue(dropped.stream().allMatch(entry -> entry.reason() == Abandon.SESSION_CHANGED));
        assertEquals(0, outbox.size());
        assertInstanceOf(Step.Idle.class, outbox.step(1));
    }
}
