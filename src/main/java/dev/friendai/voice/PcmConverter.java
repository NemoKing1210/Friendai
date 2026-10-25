package dev.friendai.voice;

import java.util.Arrays;

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
        return withGain(pcm16le, 0, pcm16le.length, gain);
    }

    /** Scales a slice exactly like {@link #withGain(byte[], double)}; a trailing odd byte is kept unchanged. */
    public static byte[] withGain(byte[] pcm16le, int offset, int length, double gain) {
        int start = Math.max(0, offset);
        int end = Math.min(pcm16le.length, start + Math.max(0, length));
        byte[] out = Arrays.copyOfRange(pcm16le, start, end);
        for (int i = 0; i + 1 < out.length; i += 2) {
            int sample = (short) ((out[i] & 0xFF) | (out[i + 1] << 8));
            int value = scaled(sample, gain);
            out[i] = (byte) value;
            out[i + 1] = (byte) (value >> 8);
        }
        return out;
    }

    /**
     * Interleaves a slice of mono 16-bit samples into stereo, scaling each channel separately.
     * Whole samples only: a trailing odd byte in the slice is ignored.
     */
    public static byte[] toStereo(byte[] pcm16le, int offset, int length, double leftGain, double rightGain) {
        int start = Math.max(0, offset);
        int end = Math.min(pcm16le.length, start + Math.max(0, length));
        int usable = (end - start) & ~1;
        byte[] out = new byte[usable * 2];
        for (int i = 0; i < usable; i += 2) {
            int sample = (short) ((pcm16le[start + i] & 0xFF) | (pcm16le[start + i + 1] << 8));
            int left = scaled(sample, leftGain);
            int right = scaled(sample, rightGain);
            int o = i * 2;
            out[o] = (byte) left;
            out[o + 1] = (byte) (left >> 8);
            out[o + 2] = (byte) right;
            out[o + 3] = (byte) (right >> 8);
        }
        return out;
    }

    private static int scaled(int sample, double gain) {
        long scaled = Math.round(sample * gain);
        return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }
}
