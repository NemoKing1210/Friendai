package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class VoiceFramesTest {
    @Test
    void aClipOfWholeFramesIsCutWithoutPadding() {
        short[] samples = ramp(2 * VoiceFrames.FRAME_SAMPLES);
        VoiceFrames frames = VoiceFrames.of(clip(samples, 48_000), 1.0);

        assertEquals(2, frames.count());
        assertArrayEquals(Arrays.copyOfRange(samples, 0, 960), frames.frame(0));
        assertArrayEquals(Arrays.copyOfRange(samples, 960, 1920), frames.frame(1));
    }

    @Test
    void theTailIsPaddedWithSilenceToAFullFrame() {
        short[] samples = ramp(VoiceFrames.FRAME_SAMPLES + 40);
        VoiceFrames frames = VoiceFrames.of(clip(samples, 48_000), 1.0);

        assertEquals(2, frames.count());
        short[] tail = frames.frame(1);
        assertEquals(VoiceFrames.FRAME_SAMPLES, tail.length);
        assertArrayEquals(Arrays.copyOfRange(samples, 960, 1000), Arrays.copyOfRange(tail, 0, 40));
        assertArrayEquals(new short[920], Arrays.copyOfRange(tail, 40, 960));
    }

    @Test
    void aClipAtAnotherRateKeepsItsDurationAndTheFrameSize() {
        // One second of audio is fifty 20 ms frames whatever rate the engine produced it at.
        for (int rate : new int[] {16_000, 22_050, 24_000, 44_100}) {
            VoiceFrames frames = VoiceFrames.of(clip(ramp(rate), rate), 1.0);
            assertEquals(50, frames.count(), "rate " + rate);
            for (int i = 0; i < frames.count(); i++) {
                assertEquals(VoiceFrames.FRAME_SAMPLES, frames.frame(i).length);
            }
        }
        // 1.01 s does not divide into frames: the 51st carries the remainder.
        assertEquals(51, VoiceFrames.of(clip(ramp(22_271), 22_050), 1.0).count());
    }

    @Test
    void aClipWithoutAWholeSampleHasNoFrames() {
        assertEquals(0, VoiceFrames.of(null, 1.0).count());
        assertEquals(0, VoiceFrames.of(new TtsClip(null, 48_000), 1.0).count());
        assertEquals(0, VoiceFrames.of(new TtsClip(new byte[0], 48_000), 1.0).count());
        assertEquals(0, VoiceFrames.of(new TtsClip(new byte[] {7}, 48_000), 1.0).count());
    }

    @Test
    void theVolumeScalesEverySampleAndClampsAtFullScale() {
        short[] samples = {1000, -1000, 30_000, -30_000};
        assertArrayEquals(
                new short[] {500, -500, 15_000, -15_000},
                Arrays.copyOfRange(VoiceFrames.of(clip(samples, 48_000), 0.5).frame(0), 0, 4));
        assertArrayEquals(
                new short[] {2000, -2000, Short.MAX_VALUE, Short.MIN_VALUE},
                Arrays.copyOfRange(VoiceFrames.of(clip(samples, 48_000), 2.0).frame(0), 0, 4));
    }

    @Test
    void aMutedClipStillTakesItsTimeToPlay() {
        VoiceFrames frames = VoiceFrames.of(clip(ramp(3 * VoiceFrames.FRAME_SAMPLES), 48_000), 0.0);

        assertEquals(3, frames.count());
        assertArrayEquals(new short[VoiceFrames.FRAME_SAMPLES], frames.frame(1));
    }

    @Test
    void aFrameOutsideTheClipIsAnError() {
        VoiceFrames frames = VoiceFrames.of(clip(ramp(960), 48_000), 1.0);
        assertThrows(IndexOutOfBoundsException.class, () -> frames.frame(1));
        assertThrows(IndexOutOfBoundsException.class, () -> frames.frame(-1));
    }

    @Test
    void anUnusableSampleRateIsReportedInsteadOfProducingNoise() {
        assertThrows(IllegalArgumentException.class, () -> VoiceFrames.of(clip(ramp(100), 0), 1.0));
    }

    private static short[] ramp(int samples) {
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (short) (i % 2000 - 1000);
        }
        return out;
    }

    private static TtsClip clip(short[] samples, int rate) {
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            pcm[2 * i] = (byte) samples[i];
            pcm[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return new TtsClip(pcm, rate);
    }
}
