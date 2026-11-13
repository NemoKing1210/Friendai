package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

class StreamDecimatorTest {
    /** One voice-chat frame: 20 ms at 48 kHz. */
    private static final int FRAME = 960;

    @Test
    void aTwentyMillisecondFrameBecomesTwentyMillisecondsAtSixteenKilohertz() {
        StreamDecimator decimator = new StreamDecimator();

        assertEquals(320 * 2, decimator.feed(tone(1_000, 10_000, 0, FRAME)).length);
        assertEquals(0, decimator.feed(new short[0]).length);
    }

    @Test
    void framesOfAnySizeGiveTheSameStreamAsOneBlock() {
        short[] signal = tone(1_000, 10_000, 0, FRAME * 6);
        byte[] whole = new StreamDecimator().feed(signal);

        StreamDecimator framed = new StreamDecimator();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        int[] sizes = {FRAME, 100, 333, 7, FRAME, 1, FRAME * 2};
        int offset = 0;
        for (int size : sizes) {
            int length = Math.min(size, signal.length - offset);
            short[] frame = new short[length];
            System.arraycopy(signal, offset, frame, 0, length);
            joined.writeBytes(framed.feed(frame));
            offset += length;
        }
        short[] rest = new short[signal.length - offset];
        System.arraycopy(signal, offset, rest, 0, rest.length);
        joined.writeBytes(framed.feed(rest));

        // A seam at a frame edge would change samples there; the filter history makes the cuts invisible.
        assertArrayEquals(whole, joined.toByteArray());
    }

    @Test
    void speechBandAudioKeepsItsLevel() {
        short[] out = PcmResampler.toShorts(new StreamDecimator().feed(tone(1_000, 10_000, 0, FRAME * 10)));

        int peak = peakAfterWarmUp(out);
        assertTrue(peak > 9_500 && peak < 10_500, "a 1 kHz tone keeps its level, got " + peak);
    }

    @Test
    void audioAboveTheNewNyquistFrequencyIsRemovedInsteadOfFoldingBack() {
        short[] out = PcmResampler.toShorts(new StreamDecimator().feed(tone(12_000, 10_000, 0, FRAME * 10)));

        int peak = peakAfterWarmUp(out);
        assertTrue(peak < 500, "12 kHz cannot exist at 16 kHz and must not alias to 4 kHz, got " + peak);
    }

    @Test
    void resetStartsAFreshStream() {
        StreamDecimator decimator = new StreamDecimator();
        byte[] first = decimator.feed(tone(1_000, 10_000, 0, FRAME));
        decimator.feed(tone(3_000, 20_000, 5, FRAME));

        decimator.reset();

        assertArrayEquals(first, decimator.feed(tone(1_000, 10_000, 0, FRAME)));
    }

    private static int peakAfterWarmUp(short[] samples) {
        int peak = 0;
        for (int i = 40; i < samples.length; i++) {
            peak = Math.max(peak, Math.abs(samples[i]));
        }
        return peak;
    }

    private static short[] tone(int hz, int amplitude, int phaseSamples, int length) {
        short[] samples = new short[length];
        for (int i = 0; i < length; i++) {
            samples[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * (i + phaseSamples) / 48_000.0));
        }
        return samples;
    }
}
