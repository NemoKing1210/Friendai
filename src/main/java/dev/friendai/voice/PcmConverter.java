package dev.friendai.voice;

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

    /** Converts floats in [-1, 1] to 16-bit little-endian signed PCM, clamping anything outside. */
    public static byte[] fromFloat(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float sample = Math.max(-1f, Math.min(1f, samples[i]));
            int value = Math.round(sample >= 0 ? sample * 32767f : sample * 32768f);
            out[2 * i] = (byte) value;
            out[2 * i + 1] = (byte) (value >> 8);
        }
        return out;
    }

    /** Scales every 16-bit sample by {@code gain}, clamping; a trailing odd byte is kept unchanged. */
    public static byte[] withGain(byte[] pcm16le, double gain) {
        byte[] out = pcm16le.clone();
        for (int i = 0; i + 1 < out.length; i += 2) {
            int sample = (short) ((out[i] & 0xFF) | (out[i + 1] << 8));
            long scaled = Math.round(sample * gain);
            int value = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            out[i] = (byte) value;
            out[i + 1] = (byte) (value >> 8);
        }
        return out;
    }
}
