package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FramePacerTest {
    private static final long FRAME = 20_000_000L;
    private static final long MS = 1_000_000L;
    private static final long START = 5_000_000_000L;

    @Test
    void theFirstFrameGoesOutAtOnce() {
        assertEquals(0, new FramePacer(FRAME, 10).waitNanos(START));
    }

    @Test
    void eachFrameIsDueOneFrameAfterTheOneBefore() {
        FramePacer pacer = new FramePacer(FRAME, 10);
        pacer.waitNanos(START);
        pacer.sent();

        assertEquals(FRAME, pacer.waitNanos(START));
        assertEquals(15 * MS, pacer.waitNanos(START + 5 * MS));
        assertEquals(0, pacer.waitNanos(START + FRAME));
    }

    @Test
    void lateWakeUpsDoNotPushTheScheduleBack() {
        FramePacer pacer = new FramePacer(FRAME, 10);
        pacer.waitNanos(START);
        pacer.sent();
        // Every frame is sent 3 ms after it was due; a relative timer would be 300 ms behind after a hundred.
        for (int i = 1; i <= 100; i++) {
            assertEquals(0, pacer.waitNanos(START + i * FRAME + 3 * MS));
            pacer.sent();
        }

        assertEquals(17 * MS, pacer.waitNanos(START + 100 * FRAME + 3 * MS));
    }

    @Test
    void aSenderThatFellBehindCatchesUpBackToBack() {
        FramePacer pacer = new FramePacer(FRAME, 10);
        pacer.waitNanos(START);
        pacer.sent();
        long late = START + 70 * MS;

        // Frames due at 20, 40 and 60 ms are overdue; the one due at 80 ms waits its turn.
        for (int i = 0; i < 3; i++) {
            assertEquals(0, pacer.waitNanos(late));
            pacer.sent();
        }
        assertEquals(10 * MS, pacer.waitNanos(late));
    }

    @Test
    void aLongStallRestartsTheScheduleInsteadOfFloodingTheSink() {
        FramePacer pacer = new FramePacer(FRAME, 10);
        pacer.waitNanos(START);
        pacer.sent();
        long stalled = START + 1_000 * MS;

        assertEquals(0, pacer.waitNanos(stalled));
        pacer.sent();
        // Fifty frames were missed, but only the current one goes out: the next waits a whole frame again.
        assertEquals(FRAME, pacer.waitNanos(stalled));
    }

    @Test
    void theLagBoundItselfStillCountsAsCatchingUp() {
        FramePacer pacer = new FramePacer(FRAME, 2);
        pacer.waitNanos(START);
        pacer.sent();

        // Exactly two frames late: the two overdue frames and the one due now go out, then the schedule is on time.
        long late = START + 3 * FRAME;
        assertEquals(0, pacer.waitNanos(late));
        pacer.sent();
        assertEquals(0, pacer.waitNanos(late));
        pacer.sent();
        assertEquals(0, pacer.waitNanos(late));
        pacer.sent();
        assertEquals(FRAME, pacer.waitNanos(late));
    }

    @Test
    void aFrameLengthOrLagBoundThatMakesNoSenseIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FramePacer(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new FramePacer(FRAME, -1));
    }
}
