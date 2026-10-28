package dev.friendai.features.speech;

import dev.friendai.features.speech.tts.TtsClip;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal RIFF/WAVE writer and reader for 16-bit mono PCM. */
public final class WavCodec {
    private static final int HEADER_BYTES = 44;

    private WavCodec() {}

    public static byte[] encode(float[] samples, int sampleRateHz) {
        return encode(PcmConverter.fromFloat(samples), sampleRateHz);
    }

    /** Writes a canonical 44-byte header followed by already encoded 16-bit little-endian samples. */
    public static byte[] encode(byte[] pcm16le, int sampleRateHz) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + pcm16le.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + pcm16le.length);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1); // PCM
        buffer.putShort((short) 1); // mono
        buffer.putInt(sampleRateHz);
        buffer.putInt(sampleRateHz * 2); // byte rate
        buffer.putShort((short) 2); // block align
        buffer.putShort((short) 16); // bits per sample
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(pcm16le.length);
        buffer.put(pcm16le);
        return buffer.array();
    }

    /** @return the samples as floats in [-1, 1) @throws IllegalArgumentException when the bytes are not 16-bit mono PCM */
    public static float[] decode(byte[] wav) {
        return PcmConverter.toFloat(parse(wav).pcm16le());
    }

    /** @return the samples with the sample rate declared by the WAV header */
    public static TtsClip decodeClip(byte[] wav) {
        Parsed parsed = parse(wav);
        return new TtsClip(parsed.pcm16le(), parsed.sampleRate());
    }

    private static Parsed parse(byte[] wav) {
        if (wav == null || wav.length < 12 || !"RIFF".equals(text(wav, 0, 4)) || !"WAVE".equals(text(wav, 8, 4))) {
            throw new IllegalArgumentException("not a RIFF/WAVE file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int offset = 12;
        int format = -1;
        int channels = -1;
        int bits = -1;
        int sampleRate = -1;
        while (offset + 8 <= wav.length) {
            String id = text(wav, offset, 4);
            long size = u32(wav, offset + 4);
            int body = offset + 8;
            if (body + size > wav.length) {
                throw new IllegalArgumentException("truncated WAV chunk " + id);
            }
            if (id.equals("fmt ")) {
                if (size < 16) {
                    throw new IllegalArgumentException("short fmt chunk");
                }
                format = buffer.getShort(body) & 0xFFFF;
                channels = buffer.getShort(body + 2) & 0xFFFF;
                sampleRate = buffer.getInt(body + 4);
                bits = buffer.getShort(body + 14) & 0xFFFF;
                if (format != 1 || channels != 1 || bits != 16 || sampleRate <= 0) {
                    throw new IllegalArgumentException("only 16-bit mono PCM is supported");
                }
            } else if (id.equals("data")) {
                if (format != 1 || channels != 1 || bits != 16) {
                    throw new IllegalArgumentException("only 16-bit mono PCM is supported");
                }
                if ((size % 2) != 0) {
                    throw new IllegalArgumentException("odd-sized data chunk");
                }
                byte[] pcm = new byte[(int) size];
                System.arraycopy(wav, body, pcm, 0, (int) size);
                return new Parsed(pcm, sampleRate);
            }
            offset = body + (int) size + (int) (size % 2);
        }
        throw new IllegalArgumentException("missing data chunk");
    }

    private record Parsed(byte[] pcm16le, int sampleRate) {}

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static long u32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF))
                | ((long) (bytes[offset + 1] & 0xFF) << 8)
                | ((long) (bytes[offset + 2] & 0xFF) << 16)
                | ((long) (bytes[offset + 3] & 0xFF) << 24);
    }
}
