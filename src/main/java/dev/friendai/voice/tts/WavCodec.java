package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Reads and writes uncompressed 16-bit mono PCM WAV in memory. */
public final class WavCodec {
    private static final int HEADER_BYTES = 44;

    private WavCodec() {}

    /** @throws UserFacingException when the bytes are not a mono 16-bit PCM WAV */
    public static TtsClip decode(byte[] wav) {
        if (wav == null || wav.length < 12
                || !"RIFF".equals(text(wav, 0, 4)) || !"WAVE".equals(text(wav, 8, 4))) {
            throw failed();
        }
        int sampleRate = -1;
        byte[] data = null;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            String id = text(wav, offset, 4);
            long size = u32(wav, offset + 4);
            int body = offset + 8;
            if (size < 0 || body + size > wav.length) {
                throw failed();
            }
            if ("fmt ".equals(id)) {
                if (size < 16) {
                    throw failed();
                }
                int format = u16(wav, body);
                int channels = u16(wav, body + 2);
                sampleRate = (int) u32(wav, body + 4);
                int bits = u16(wav, body + 14);
                if (format != 1 || channels != 1 || bits != 16 || sampleRate <= 0) {
                    throw failed();
                }
            } else if ("data".equals(id)) {
                if ((size & 1) != 0) {
                    throw failed();
                }
                data = new byte[(int) size];
                System.arraycopy(wav, body, data, 0, (int) size);
            }
            offset = body + (int) size + ((int) size & 1);
        }
        if (sampleRate < 0 || data == null) {
            throw failed();
        }
        return new TtsClip(data, sampleRate);
    }

    /** Writes a canonical 44-byte header followed by the samples. */
    public static byte[] encode(TtsClip clip) {
        byte[] pcm = clip.pcm16le();
        int rate = clip.sampleRate();
        ByteBuffer out = ByteBuffer.allocate(HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        out.put("fmt ".getBytes()).putInt(16);
        out.putShort((short) 1).putShort((short) 1).putInt(rate);
        out.putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return out.array();
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF))
                | ((long) (bytes[offset + 1] & 0xFF) << 8)
                | ((long) (bytes[offset + 2] & 0xFF) << 16)
                | ((long) (bytes[offset + 3] & 0xFF) << 24);
    }
}
