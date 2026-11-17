package dev.friendai.features.speech;

import java.util.Arrays;

/**
 * Turns a stream of 48 kHz mono frames into 16 kHz PCM, keeping the filter's history across frames so a frame
 * boundary is not a seam. Voice chat hands over the player's microphone in 20 ms frames of 960 samples, while the local
 * wake word and the recognizers listen at 16 kHz; resampling every frame on its own would click at every edge.
 *
 * <p>A causal windowed-sinc low-pass below the new Nyquist frequency, then every third sample. Not thread-safe: one
 * stream has one caller.
 */
public final class StreamDecimator {
    public static final int FROM_HZ = 48_000;
    public static final int TO_HZ = 16_000;

    private static final int FACTOR = FROM_HZ / TO_HZ;
    /** Sinc zero crossings kept on each side of the centre; one crossing spans {@link #FACTOR} input samples. */
    private static final int LOBES = 8;
    /** A little under the new Nyquist frequency, so the band edge does not fold back as an alias. */
    private static final double CUTOFF = 0.9 / FACTOR;

    private static final int HALF = LOBES * FACTOR;
    private static final int TAPS = 2 * HALF + 1;
    private static final float[] KERNEL = kernel();

    /** The last {@code TAPS - 1} input samples, oldest first; silence before the stream began. */
    private final short[] history = new short[TAPS - 1];
    /** Input samples taken since the last output, so the one-in-three rhythm survives frames of any length. */
    private int phase;

    /** Filters and decimates one frame; returns 16-bit little-endian 16 kHz PCM, empty when no output is due yet. */
    public byte[] feed(short[] frame) {
        if (frame == null || frame.length == 0) {
            return new byte[0];
        }
        short[] buffer = new short[history.length + frame.length];
        System.arraycopy(history, 0, buffer, 0, history.length);
        System.arraycopy(frame, 0, buffer, history.length, frame.length);
        byte[] out = new byte[((frame.length + phase) / FACTOR) * 2];
        int written = 0;
        for (int i = 0; i < frame.length; i++) {
            if (++phase < FACTOR) {
                continue;
            }
            phase = 0;
            int newest = history.length + i;
            double sum = 0.0;
            for (int tap = 0; tap < TAPS; tap++) {
                sum += buffer[newest - tap] * KERNEL[tap];
            }
            short sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum)));
            out[written++] = (byte) sample;
            out[written++] = (byte) (sample >> 8);
        }
        System.arraycopy(buffer, buffer.length - history.length, history, 0, history.length);
        return written == out.length ? out : Arrays.copyOf(out, written);
    }

    /** Forgets the history, as at the start of a new stream. */
    public void reset() {
        Arrays.fill(history, (short) 0);
        phase = 0;
    }

    /** A symmetric low-pass normalized to unit gain, so a steady level keeps its value. */
    private static float[] kernel() {
        double[] taps = new double[TAPS];
        double total = 0.0;
        for (int tap = 0; tap < TAPS; tap++) {
            double offset = tap - HALF;
            taps[tap] = sinc(offset * CUTOFF) * window(offset / (HALF + 1));
            total += taps[tap];
        }
        float[] kernel = new float[TAPS];
        for (int tap = 0; tap < TAPS; tap++) {
            kernel[tap] = (float) (taps[tap] / total);
        }
        return kernel;
    }

    private static double sinc(double x) {
        if (Math.abs(x) < 1e-9) {
            return 1.0;
        }
        double angle = Math.PI * x;
        return Math.sin(angle) / angle;
    }

    /** Lanczos window over the whole kernel, zero at its ends. */
    private static double window(double x) {
        return Math.abs(x) >= 1.0 ? 0.0 : sinc(x);
    }
}
