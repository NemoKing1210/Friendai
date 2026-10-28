package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PcmConverterTest {
    @Test
    void zeroIsZero() {
        assertEquals(0f, PcmConverter.toFloat(new byte[] {0x00, 0x00})[0]);
    }

    @Test
    void maxPositive() {
        assertEquals(32767 / 32768f, PcmConverter.toFloat(new byte[] {(byte) 0xFF, 0x7F})[0]);
    }

    @Test
    void minNegative() {
        assertEquals(-1f, PcmConverter.toFloat(new byte[] {0x00, (byte) 0x80})[0]);
    }

    @Test
    void lengthIsHalfTheBytes() {
        assertEquals(3, PcmConverter.toFloat(new byte[6]).length);
        assertEquals(2, PcmConverter.toFloat(new byte[5]).length);
    }

    @Test
    void lowByteIsUnsigned() {
        assertEquals(255 / 32768f, PcmConverter.toFloat(new byte[] {(byte) 0xFF, 0x00})[0]);
    }

    @Test
    void floatToPcm16ClampsAndIsLittleEndian() {
        assertArrayEquals(
                new byte[] {0, 0, 0x00, 0x40, 0x00, (byte) 0xC0, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80},
                PcmConverter.fromFloat(new float[] {0f, 0.5f, -0.5f, 1.5f, -1.5f}));
    }

    @Test
    void gainIsAppliedOnce() {
        assertArrayEquals(new byte[] {0x00, 0x20}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 0.5));
        assertArrayEquals(new byte[] {0x00, 0x00}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 0.0));
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x7F}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 4.0));
    }

    @Test
    void stereoInterleavesWithPerChannelGains() {
        assertArrayEquals(
                new byte[] {0x00, 0x20, 0x00, 0x40}, PcmConverter.toStereo(new byte[] {0x00, 0x40}, 0, 2, 0.5, 1.0));
    }

    @Test
    void stereoClampsEachChannel() {
        assertArrayEquals(
                new byte[] {(byte) 0xFF, 0x7F, (byte) 0xFF, 0x7F},
                PcmConverter.toStereo(new byte[] {0x00, 0x40}, 0, 2, 4.0, 4.0));
    }

    @Test
    void stereoHonoursOffsetAndLength() {
        byte[] pcm = new byte[] {(byte) 0xAA, (byte) 0xBB, 0x00, 0x40, 0x00, (byte) 0xC0, (byte) 0xCC};
        assertArrayEquals(new byte[] {0x00, 0x20, 0x00, 0x30}, PcmConverter.toStereo(pcm, 2, 3, 0.5, 0.75));
    }

    @Test
    void stereoIgnoresATrailingOddByte() {
        assertEquals(4, PcmConverter.toStereo(new byte[] {0x00, 0x40, 0x7F}, 0, 3, 1.0, 1.0).length);
    }

    @Test
    void rangedGainScalesOnlyTheSlice() {
        byte[] pcm = new byte[] {0x00, 0x40, 0x00, 0x40, 0x00, 0x40};
        assertArrayEquals(new byte[] {0x00, 0x20, 0x00, 0x20}, PcmConverter.withGain(pcm, 2, 4, 0.5));
    }

    @Test
    void rangedGainClamps() {
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x7F}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 0, 2, 4.0));
    }

    @Test
    void rampedStereoReachesEachChannelFromStartToEnd() {
        // Four samples of 0x4000: left runs 0 -> 1, right runs 1 -> 0 across the block.
        byte[] pcm = new byte[] {0x00, 0x40, 0x00, 0x40, 0x00, 0x40, 0x00, 0x40};
        assertArrayEquals(
                new byte[] {
                    0x00, 0x00, 0x00, 0x40, 0x00, 0x10, 0x00, 0x30, 0x00, 0x20, 0x00, 0x20, 0x00, 0x30, 0x00, 0x10
                },
                PcmConverter.toStereoRamped(pcm, 0, 8, 0.0, 1.0, 1.0, 0.0));
    }

    @Test
    void rampedStereoWithEqualEndsMatchesTheStaticMix() {
        byte[] pcm = new byte[] {0x00, 0x40, 0x00, (byte) 0xC0, 0x00, 0x20};
        assertArrayEquals(
                PcmConverter.toStereo(pcm, 0, 6, 0.5, 0.75),
                PcmConverter.toStereoRamped(pcm, 0, 6, 0.5, 0.75, 0.5, 0.75));
    }

    @Test
    void rampedStereoIgnoresATrailingOddByte() {
        assertEquals(4, PcmConverter.toStereoRamped(new byte[] {0x00, 0x40, 0x7F}, 0, 3, 0, 0, 1, 1).length);
    }

    @Test
    void rampedGainRunsFromStartToEnd() {
        byte[] pcm = new byte[] {0x00, 0x40, 0x00, 0x40, 0x00, 0x40, 0x00, 0x40};
        assertArrayEquals(
                new byte[] {0x00, 0x00, 0x00, 0x10, 0x00, 0x20, 0x00, 0x30},
                PcmConverter.withGainRamped(pcm, 0, 8, 0.0, 1.0));
    }
}
