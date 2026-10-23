package dev.friendai.voice;

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
}
