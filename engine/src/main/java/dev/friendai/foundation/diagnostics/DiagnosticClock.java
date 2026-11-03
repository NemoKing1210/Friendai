package dev.friendai.foundation.diagnostics;

/**
 * The two time sources diagnostics needs: wall time for human correlation and a monotonic source for measured
 * durations. The monotonic source is what keeps a clock rollback from producing a negative duration.
 */
public interface DiagnosticClock {
    /** UTC epoch milliseconds. */
    long wallMillis();

    /** A monotonic nanosecond counter; never related to {@link #wallMillis()} except by its own deltas. */
    long nanoTime();

    static DiagnosticClock system() {
        return SystemClock.INSTANCE;
    }

    /** The process clock: {@link System#currentTimeMillis()} and {@link System#nanoTime()}. */
    final class SystemClock implements DiagnosticClock {
        private static final SystemClock INSTANCE = new SystemClock();

        private SystemClock() {}

        @Override
        public long wallMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }
}
