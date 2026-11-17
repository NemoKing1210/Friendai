package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PcmResamplerTest {
    private static final int AMPLITUDE = 10_000;

    @Test
    void decodesLittleEndianSamplesAndDropsAStrayByte() {
        assertArrayEquals(new short[] {1, -2, Short.MAX_VALUE, Short.MIN_VALUE}, PcmResampler.toShorts(new byte[] {
            0x01, 0x00, (byte) 0xFE, (byte) 0xFF, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80, 0x55
        }));
    }

    @Test
    void theSameRateKeepsEverySample() {
        short[] input = {1, -2, 300, -400};
        assertArrayEquals(input, PcmResampler.resample(input, 48_000, 48_000));
    }

    @Test
    void theResultCoversTheSameDurationRoundedUpToASample() {
        assertEquals(200, PcmResampler.resample(new short[100], 24_000, 48_000).length);
        assertEquals(960, PcmResampler.resample(new short[441], 22_050, 48_000).length);
        assertEquals(160, PcmResampler.resample(new short[147], 44_100, 48_000).length);
        assertEquals(3, PcmResampler.resample(new short[1], 16_000, 48_000).length);
        assertEquals(1, PcmResampler.resample(new short[1], 96_000, 48_000).length);
        assertEquals(0, PcmResampler.resample(new short[0], 22_050, 48_000).length);
    }

    @Test
    void aSteadyLevelKeepsItsValueAwayFromTheClipEdges() {
        short[] input = new short[2_000];
        java.util.Arrays.fill(input, (short) AMPLITUDE);
        for (int rate : new int[] {16_000, 22_050, 24_000, 44_100, 96_000}) {
            short[] out = PcmResampler.resample(input, rate, 48_000);
            for (int i = out.length / 4; i < out.length * 3 / 4; i++) {
                assertEquals(AMPLITUDE, out[i], 1, "rate " + rate + ", sample " + i);
            }
        }
    }

    @Test
    void aToneKeepsItsPitchPhaseAndLoudnessWhenUpsampled() {
        // 5 kHz is close enough to the 11 kHz limit of a 22.05 kHz clip that joining the dots would distort it.
        for (int rate : new int[] {16_000, 22_050, 24_000, 44_100}) {
            short[] out = PcmResampler.resample(tone(5_000, rate, rate / 5), rate, 48_000);
            assertTrue(worstError(out, 5_000, 48_000) < AMPLITUDE * 0.02, "rate " + rate);
        }
    }

    @Test
    void downsamplingRemovesWhatTheTargetRateCannotCarry() {
        // 30 kHz is above the 24 kHz limit of a 48 kHz stream: left in, it would fold back as an audible 18 kHz tone.
        short[] folded = PcmResampler.resample(tone(30_000, 96_000, 19_200), 96_000, 48_000);
        assertTrue(peak(folded) < AMPLITUDE * 0.05, "peak " + peak(folded));

        short[] kept = PcmResampler.resample(tone(1_000, 96_000, 19_200), 96_000, 48_000);
        assertTrue(worstError(kept, 1_000, 48_000) < AMPLITUDE * 0.02);
    }

    @Test
    void anOvershootAtFullScaleIsClampedInsteadOfWrappingAround() {
        // Silence followed by full scale rings past the 16-bit limit; a wrapped sample would flip to about -32768.
        short[] input = new short[400];
        java.util.Arrays.fill(input, 200, 400, Short.MAX_VALUE);
        short[] out = PcmResampler.resample(input, 22_050, 48_000);
        int lowest = 0;
        int highest = 0;
        for (short sample : out) {
            lowest = Math.min(lowest, sample);
            highest = Math.max(highest, sample);
        }
        assertEquals(Short.MAX_VALUE, highest);
        assertTrue(lowest > -8_000, "lowest " + lowest);
    }

    @Test
    void ratesThatAreNotAudioAreRejected() {
        short[] input = {1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> PcmResampler.resample(input, 0, 48_000));
        assertThrows(IllegalArgumentException.class, () -> PcmResampler.resample(input, -8_000, 48_000));
        assertThrows(IllegalArgumentException.class, () -> PcmResampler.resample(input, 48_000, 0));
        assertThrows(IllegalArgumentException.class, () -> PcmResampler.resample(input, 1_000_000, 48_000));
    }

    private static short[] tone(int hz, int rate, int samples) {
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (short) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return out;
    }

    /** The largest difference from the ideal tone, skipping the edges where the clip meets silence. */
    private static double worstError(short[] out, int hz, int rate) {
        double worst = 0;
        for (int i = out.length / 4; i < out.length * 3 / 4; i++) {
            double ideal = AMPLITUDE * Math.sin(2 * Math.PI * hz * i / rate);
            worst = Math.max(worst, Math.abs(out[i] - ideal));
        }
        return worst;
    }

    private static int peak(short[] out) {
        int peak = 0;
        for (int i = out.length / 4; i < out.length * 3 / 4; i++) {
            peak = Math.max(peak, Math.abs(out[i]));
        }
        return peak;
    }
}
