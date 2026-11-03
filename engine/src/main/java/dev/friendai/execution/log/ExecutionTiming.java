package dev.friendai.execution.log;

import dev.friendai.foundation.diagnostics.DiagnosticClock;

/**
 * Counts one task activation's time without pretending that every wall millisecond was work.
 *
 * <p>{@code activeTicks} counts invocations actually delivered to the task, including its legitimate waiting ticks;
 * {@code heldTicks} counts runner ticks observed under an explicit hold, separately, and is never folded into the
 * active count. {@code tickNanos} is the aggregate time spent inside the instrumented task callback, not CPU time and
 * not time the companion spent walking in the world. A held interval still contributes to wall time; only a
 * server-wide pause is unknowable, which is why a full pause is reported as an unknown gap rather than a hold.
 */
public final class ExecutionTiming {
    /** An immutable reading of one activation: elapsed wall time and the two tick counters. */
    public record Snapshot(long wallMs, long activeTicks, long heldTicks, long tickNanos) {}

    private final DiagnosticClock clock;
    private final long startedWallMs;
    private final long startedNanos;
    private long activeTicks;
    private long heldTicks;
    private long tickNanos;

    private ExecutionTiming(DiagnosticClock clock) {
        this.clock = clock == null ? DiagnosticClock.system() : clock;
        this.startedWallMs = this.clock.wallMillis();
        this.startedNanos = this.clock.nanoTime();
    }

    public static ExecutionTiming started(DiagnosticClock clock) {
        return new ExecutionTiming(clock);
    }

    /** One invocation was delivered to the task; {@code processingNanos} is the measured callback time. */
    public void onTick(long processingNanos) {
        activeTicks++;
        tickNanos += Math.max(0L, processingNanos);
    }

    /** One runner tick was observed under an explicit hold: counted apart from active work, never as progress. */
    public void onHoldTick() {
        heldTicks++;
    }

    public Snapshot snapshot() {
        long wall = clock.wallMillis() - startedWallMs;
        long monotonic = clock.nanoTime() - startedNanos;
        return new Snapshot(Math.max(0L, wall), activeTicks, heldTicks, Math.max(0L, tickNanos));
    }

    /** The monotonic elapsed interval in milliseconds, safe against a wall-clock rollback. */
    public long elapsedMs() {
        return Math.max(0L, (clock.nanoTime() - startedNanos) / 1_000_000L);
    }
}
