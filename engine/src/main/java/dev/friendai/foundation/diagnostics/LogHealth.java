package dev.friendai.foundation.diagnostics;

/**
 * An immutable snapshot of the durable writer: how much was admitted, processed, written and lost, how deep the
 * queue is now and how deep it went, when the last flush finished, and whether the writer is healthy.
 */
public record LogHealth(
        long accepted,
        long processed,
        long written,
        long dropped,
        long errors,
        long droppedSinceFlush,
        int depth,
        int highWater,
        long lastFlushMillis,
        State state) {

    public enum State {
        OPEN,
        CLOSED,
        FAILED
    }

    public LogHealth {
        droppedSinceFlush = Math.max(0L, droppedSinceFlush);
        depth = Math.max(0, depth);
        highWater = Math.max(depth, highWater);
        state = state == null ? State.OPEN : state;
    }

    /** True when the writer still accepts records and has not hit a file error. */
    public boolean healthy() {
        return state == State.OPEN;
    }
}
