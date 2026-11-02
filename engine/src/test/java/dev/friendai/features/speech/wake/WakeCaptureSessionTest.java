package dev.friendai.features.speech.wake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WakeCaptureSessionTest {
    private static WakeWordPolicy policy() {
        return new WakeWordPolicy(new WakeWordPolicy.Config(true, 5, 4, 2));
    }

    private static byte[] pcm(int value, int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, (byte) value);
        return out;
    }

    @Test
    void aHitStartsCaptureWithThePreRollAndSilenceEndsIt() {
        AtomicBoolean detect = new AtomicBoolean(false);
        WakeCaptureSession session = new WakeCaptureSession(policy(), (bytes, len) -> detect.get(), 16);
        session.arm();
        session.feed(pcm(1, 4), 4, false);
        assertTrue(session.preRoll().length > 0, "pre-roll is kept before a hit");
        detect.set(true);
        session.feed(pcm(2, 4), 4, true);
        assertTrue(session.capturing());
        detect.set(false);
        java.util.Optional<byte[]> finished = java.util.Optional.empty();
        for (int i = 0; i < 4 && finished.isEmpty(); i++) {
            session.feed(pcm(3, 3), 3, false);
            finished = session.tick();
        }
        assertTrue(finished.isPresent(), "silence must end capture");
        byte[] utterance = finished.orElseThrow();
        assertTrue(utterance.length >= 3, "the utterance includes pre-roll and captured chunks");
        assertArrayEquals(pcm(3, 3), java.util.Arrays.copyOfRange(utterance, utterance.length - 3, utterance.length));
        assertTrue(!session.capturing());
    }

    @Test
    void theMaximumUtteranceEndsCapture() {
        WakeCaptureSession session = new WakeCaptureSession(policy(), (bytes, len) -> false, 4);
        session.arm();
        byte[] out = null;
        for (int i = 0; i < 8; i++) {
            var result = session.tick();
            if (result.isPresent()) {
                out = result.get();
                break;
            }
        }
        assertTrue(out == null, "no capture before a hit");
        session.disarm();
        assertEquals(WakeWordPolicy.State.IDLE, session.state());
    }

    @Test
    void disarmDropsThePreRollAndNeverReturnsAudio() {
        WakeCaptureSession session = new WakeCaptureSession(policy(), (bytes, len) -> true, 8);
        session.arm();
        session.feed(pcm(1, 4), 4, true);
        assertTrue(session.capturing());
        session.disarm();
        assertTrue(!session.capturing());
        assertArrayEquals(new byte[0], session.preRoll());
        assertTrue(session.feed(pcm(2, 4), 4, true).isEmpty());
    }
}
