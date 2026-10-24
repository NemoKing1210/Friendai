package dev.friendai.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AudioLevelTest {
    @Test
    void silenceIsZero() {
        assertEquals(0f, AudioLevel.of(new byte[200], 200));
    }

    @Test
    void fullScaleIsOne() {
        assertEquals(1f, AudioLevel.of(alternating(32767, 16), 16 * 2));
    }

    @Test
    void quietSpeechIsStillVisible() {
        float level = AudioLevel.of(alternating(3277, 16), 16 * 2);
        assertTrue(level > 0.35f && level < 0.7f, "expected roughly half scale, got " + level);
    }

    @Test
    void levelRisesWithAmplitude() {
        float quiet = AudioLevel.of(alternating(1000, 16), 32);
        float loud = AudioLevel.of(alternating(30000, 16), 32);
        assertTrue(loud > quiet, "loud " + loud + " should exceed quiet " + quiet);
    }

    @Test
    void clampsToOne() {
        assertEquals(1f, AudioLevel.of(alternating(32767, 16), 32));
    }

    @Test
    void onlyCountsTheGivenLength() {
        byte[] pcm = alternating(32767, 16);
        assertEquals(0f, AudioLevel.of(pcm, 0));
        assertEquals(1f, AudioLevel.of(pcm, 32));
    }

    @Test
    void ignoresATrailingOddByte() {
        byte[] pcm = alternating(32767, 16);
        assertEquals(1f, AudioLevel.of(pcm, 33));
    }

    @Test
    void emptyInputIsZero() {
        assertEquals(0f, AudioLevel.of(new byte[0], 0));
    }

    /** {@code samples} samples alternating between +amplitude and -amplitude, as 16-bit little-endian. */
    private static byte[] alternating(int amplitude, int samples) {
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            int value = (i % 2 == 0) ? amplitude : -amplitude;
            pcm[2 * i] = (byte) (value & 0xFF);
            pcm[2 * i + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }
}
