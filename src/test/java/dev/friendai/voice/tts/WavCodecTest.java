package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.friendai.text.UserFacingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class WavCodecTest {
    @Test
    void roundTripsPcm16Mono() {
        TtsClip clip = new TtsClip(new byte[] {0, 0, 0x10, 0x00}, 24000);
        TtsClip decoded = WavCodec.decode(WavCodec.encode(clip));
        assertEquals(24000, decoded.sampleRate());
        assertArrayEquals(clip.pcm16le(), decoded.pcm16le());
    }

    @Test
    void readsTheSampleRateFromTheHeader() {
        assertEquals(16000, WavCodec.decode(WavCodec.encode(new TtsClip(new byte[4], 16000))).sampleRate());
    }

    @Test
    void rejectsStereo8BitTruncatedAndOddData() {
        UserFacingException e = assertThrows(UserFacingException.class, () -> WavCodec.decode(new byte[] {1, 2, 3}));
        assertEquals("friendai.voice.tts.error.failed", e.userMessage().key());
        assertThrows(UserFacingException.class, () -> WavCodec.decode(wavWith(1, 2, 8, new byte[4])));
        assertThrows(UserFacingException.class, () -> WavCodec.decode(wavWith(1, 1, 16, new byte[] {1, 2, 3})));
    }

    @Test
    void skipsUnknownChunksBeforeData() {
        // A "LIST" chunk between "fmt " and "data" must be ignored.
        assertArrayEquals(new byte[] {1, 0, 2, 0}, WavCodec.decode(wavWithListChunk()).pcm16le());
    }

    private static byte[] wavWith(int format, int channels, int bits, byte[] data) {
        return wav(format, channels, bits, 8000, data, false);
    }

    private static byte[] wavWithListChunk() {
        return wav(1, 1, 16, 8000, new byte[] {1, 0, 2, 0}, true);
    }

    private static byte[] wav(int format, int channels, int bits, int rate, byte[] data, boolean listChunk) {
        int fmtSize = 16;
        int listSize = listChunk ? 16 : 0;
        ByteBuffer b = ByteBuffer.allocate(12 + 8 + fmtSize + (listChunk ? 8 + 8 : 0) + 8 + data.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(4 + (8 + fmtSize) + listSize + (8 + data.length)).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(fmtSize);
        b.putShort((short) format).putShort((short) channels).putInt(rate);
        b.putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits);
        if (listChunk) {
            b.put("LIST".getBytes()).putInt(8).put("INFOjunk".getBytes());
        }
        b.put("data".getBytes()).putInt(data.length).put(data);
        return b.array();
    }
}
