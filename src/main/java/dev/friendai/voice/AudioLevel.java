package dev.friendai.voice;

/** Turns a chunk of 16-bit PCM into a 0..1 loudness value, for animating a microphone indicator. */
public final class AudioLevel {
    /**
     * Speech RMS is small next to full scale (quiet speech is around 0.01-0.05), so the square root lifts it into the
     * range where the bars visibly move; the gain keeps normal speech near the top without clipping it there.
     */
    private static final double GAIN = 1.6;

    private AudioLevel() {}

    /** @param length how many bytes of {@code pcm16le} are valid; a trailing odd byte is ignored */
    public static float of(byte[] pcm16le, int length) {
        int samples = Math.min(length, pcm16le.length) / 2;
        if (samples <= 0) {
            return 0f;
        }
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            int sample = (pcm16le[2 * i + 1] << 8) | (pcm16le[2 * i] & 0xFF);
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / samples) / 32768.0;
        return (float) Math.min(1.0, Math.sqrt(rms) * GAIN);
    }
}
