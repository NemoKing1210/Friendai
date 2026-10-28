package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.features.speech.tts.TtsClip;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WavCodecTest {
    @Test
    void floatSamplesAreEncodedAndDecoded() {
        byte[] wav = WavCodec.encode(new float[] {0f, 0.5f}, 16000);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(44 + 2 * 2, wav.length);

        float[] back = WavCodec.decode(wav);
        assertEquals(2, back.length);
        assertEquals(0.5f, back[1], 1e-4);
    }

    @Test
    void pcmBytesRoundTripThroughDecodeClip() {
        byte[] pcm = {0, 0, 0x10, 0x00};
        TtsClip clip = WavCodec.decodeClip(WavCodec.encode(pcm, 24000));
        assertEquals(24000, clip.sampleRate());
        assertArrayEquals(pcm, clip.pcm16le());
    }

    @Test
    void unknownChunksBeforeDataAreSkipped() {
        byte[] wav = wav(1, 1, 16, 8000, new byte[] {1, 0, 2, 0}, true);
        assertArrayEquals(new byte[] {1, 0, 2, 0}, WavCodec.decodeClip(wav).pcm16le());
    }

    @Test
    void malformedInputThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> WavCodec.decode(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.decodeClip(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.decode(wav(1, 2, 16, 8000, new byte[4], false)));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.decode(wav(1, 1, 8, 8000, new byte[4], false)));
        assertThrows(
                IllegalArgumentException.class,
                () -> WavCodec.decode(wav(1, 1, 16, 8000, new byte[] {1, 2, 3}, false)));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.decode(truncated()));
    }

    @Test
    void scaledVolumeClampsAndLeavesTheOriginalAlone() {
        PcmAudio original = new PcmAudio(new byte[] {0, 0x40}, 16000); // 16384
        PcmAudio quiet = original.scaled(0.5);
        assertEquals(8192, (quiet.data()[1] << 8) | (quiet.data()[0] & 0xFF));
        PcmAudio loud = original.scaled(2.0);
        assertEquals(32767, (loud.data()[1] << 8) | (loud.data()[0] & 0xFF));
        assertEquals(16384, (original.data()[1] << 8) | (original.data()[0] & 0xFF));
    }

    @Test
    void gainIsAppliedOnce() {
        assertArrayEquals(new byte[] {0x00, 0x20}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 0.5));
        assertArrayEquals(new byte[] {0x00, 0x00}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 0.0));
        assertArrayEquals(new byte[] {(byte) 0xFF, 0x7F}, PcmConverter.withGain(new byte[] {0x00, 0x40}, 4.0));
    }

    private static byte[] truncated() {
        byte[] wav = wav(1, 1, 16, 8000, new byte[] {1, 0, 2, 0}, false);
        return java.util.Arrays.copyOf(wav, 30);
    }

    private static byte[] wav(int format, int channels, int bits, int rate, byte[] data, boolean listChunk) {
        int fmtSize = 16;
        int listSize = listChunk ? 16 : 0;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + fmtSize + listSize + 8 + data.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes())
                .putInt(4 + (8 + fmtSize) + listSize + (8 + data.length))
                .put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(fmtSize);
        b.putShort((short) format).putShort((short) channels).putInt(rate);
        b.putInt(rate * channels * bits / 8)
                .putShort((short) (channels * bits / 8))
                .putShort((short) bits);
        if (listChunk) {
            b.put("LIST".getBytes()).putInt(8).put("INFOjunk".getBytes());
        }
        b.put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }
}
