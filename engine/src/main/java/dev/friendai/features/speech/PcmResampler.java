package dev.friendai.features.speech;

/**
 * Band-limited sample-rate conversion for 16-bit mono PCM. The kernel is a windowed sinc evaluated once per output
 * phase, so a clip costs one table and a multiply-add per tap; samples outside the clip count as silence.
 */
public final class PcmResampler {
    /** Sinc zero crossings kept on each side of the centre tap. */
    private static final int LOBES = 8;
    /** Rates outside this range are not audio any engine produces, and would only inflate the kernel table. */
    private static final int MAX_RATE_HZ = 384_000;

    private PcmResampler() {}

    /** Decodes 16-bit little-endian signed PCM. A trailing odd byte cannot form a sample and is ignored. */
    public static short[] toShorts(byte[] pcm16le) {
        short[] out = new short[pcm16le.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((pcm16le[2 * i] & 0xFF) | (pcm16le[2 * i + 1] << 8));
        }
        return out;
    }

    /**
     * Converts {@code input} from {@code fromHz} to {@code toHz}. The result covers the same duration, rounded up
     * to a whole sample, and is clamped to the 16-bit range instead of wrapping.
     *
     * @throws IllegalArgumentException when a rate is not positive, is implausibly high, or the result cannot fit
     */
    public static short[] resample(short[] input, int fromHz, int toHz) {
        if (fromHz <= 0 || toHz <= 0 || fromHz > MAX_RATE_HZ || toHz > MAX_RATE_HZ) {
            throw new IllegalArgumentException("unsupported sample rate: " + fromHz + " -> " + toHz);
        }
        if (input.length == 0) {
            return new short[0];
        }
        if (fromHz == toHz) {
            return input.clone();
        }
        long outLength = ((long) input.length * toHz + fromHz - 1) / fromHz;
        if (outLength > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("clip is too long to resample");
        }
        int divisor = gcd(fromHz, toHz);
        // Output sample n sits at input position n * step / phases, so only `phases` distinct offsets exist.
        int step = fromHz / divisor;
        int phases = toHz / divisor;
        // Downsampling lowers the cutoff to the target's Nyquist frequency, which widens the kernel to match.
        double cutoff = Math.min(1.0, (double) toHz / fromHz);
        int reach = (int) Math.ceil(LOBES / cutoff);
        int taps = 2 * reach;
        float[] kernel = kernel(phases, taps, reach, cutoff);

        short[] out = new short[(int) outLength];
        for (int n = 0; n < out.length; n++) {
            long position = (long) n * step;
            int first = (int) (position / phases) - reach + 1;
            int row = (int) (position % phases) * taps;
            double sum = 0.0;
            for (int tap = Math.max(0, -first); tap < taps; tap++) {
                int index = first + tap;
                if (index >= input.length) {
                    break;
                }
                sum += input[index] * kernel[row + tap];
            }
            out[n] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sum)));
        }
        return out;
    }

    /** One row of taps per phase, each normalized to unit gain so a steady level keeps its value. */
    private static float[] kernel(int phases, int taps, int reach, double cutoff) {
        float[] kernel = new float[phases * taps];
        double[] row = new double[taps];
        for (int phase = 0; phase < phases; phase++) {
            double fraction = (double) phase / phases;
            double total = 0.0;
            for (int tap = 0; tap < taps; tap++) {
                double distance = (tap - reach + 1 - fraction) * cutoff;
                row[tap] = sinc(distance) * window(distance / LOBES);
                total += row[tap];
            }
            for (int tap = 0; tap < taps; tap++) {
                kernel[phase * taps + tap] = (float) (row[tap] / total);
            }
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

    /** Lanczos window: the sinc's own main lobe stretched over the kernel, zero outside it. */
    private static double window(double x) {
        return Math.abs(x) >= 1.0 ? 0.0 : sinc(x);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }
}
