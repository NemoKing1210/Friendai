package dev.friedai.voice;

public final class PcmConverter {
    private PcmConverter() {}

    /** Converts 16-bit little-endian signed PCM to floats in [-1, 1). A trailing odd byte is ignored. */
    public static float[] toFloat(byte[] pcm16le) {
        float[] out = new float[pcm16le.length / 2];
        for (int i = 0; i < out.length; i++) {
            int low = pcm16le[2 * i] & 0xFF;
            int high = pcm16le[2 * i + 1];
            out[i] = ((high << 8) | low) / 32768f;
        }
        return out;
    }
}
