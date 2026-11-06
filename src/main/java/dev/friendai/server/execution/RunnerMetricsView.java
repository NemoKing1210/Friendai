package dev.friendai.server.execution;

import dev.friendai.agent.verify.LoopMetrics;

/** The owner-facing loop counters of one task runner: verified outcomes, refusals, repeated remedies and stops. */
final class RunnerMetricsView {
    private final LoopMetrics loopMetrics = new LoopMetrics();
    private long interruptIssuedAt = -1;

    LoopMetrics metrics() {
        return loopMetrics;
    }

    /** Records an owner interrupt issued at a game tick; its wait is counted when the runner falls idle. */
    void interruptIssued(long tick) {
        interruptIssuedAt = tick;
        loopMetrics.interruptIssued();
    }

    /** True between an issued interrupt and the tick its wait is counted. */
    boolean interruptPending() {
        return interruptIssuedAt >= 0;
    }

    /**
     * Moves a long-blocked stop onto the interrupt counter once the wait really ended, and clears the pending
     * interrupt. Does nothing when no interrupt is pending.
     */
    void interruptCleared(long tick) {
        if (interruptIssuedAt < 0) return;
        long waited = tick - interruptIssuedAt;
        loopMetrics.interruptCleared((int) Math.min(Integer.MAX_VALUE, Math.max(0L, waited)));
        interruptIssuedAt = -1;
    }
}
