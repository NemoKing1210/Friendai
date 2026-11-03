package dev.friendai.execution.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.DiagnosticClock;
import org.junit.jupiter.api.Test;

class ExecutionTimingTest {
    private static final class FakeClock implements DiagnosticClock {
        long wall = 1_700_000_000_000L;
        long nanos = 9_000_000_000L;

        @Override
        public long wallMillis() {
            return wall;
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        void advance(long millis) {
            wall += millis;
            nanos += millis * 1_000_000L;
        }
    }

    @Test
    void aFreshActivationStartsWithNoWork() {
        ExecutionTiming timing = ExecutionTiming.started(new FakeClock());

        ExecutionTiming.Snapshot snapshot = timing.snapshot();

        assertEquals(0L, snapshot.wallMs());
        assertEquals(0L, snapshot.activeTicks());
        assertEquals(0L, snapshot.heldTicks());
        assertEquals(0L, snapshot.tickNanos());
    }

    @Test
    void activeAndHeldTicksAreCountedSeparately() {
        FakeClock clock = new FakeClock();
        ExecutionTiming timing = ExecutionTiming.started(clock);
        for (int i = 0; i < 100; i++) {
            clock.advance(50L);
            timing.onTick(2_000_000L);
        }
        for (int i = 0; i < 100; i++) {
            clock.advance(50L);
            timing.onHoldTick();
        }

        ExecutionTiming.Snapshot snapshot = timing.snapshot();

        assertEquals(100L, snapshot.activeTicks(), "a hold is never an invocation");
        assertEquals(100L, snapshot.heldTicks());
        assertEquals(50L, snapshot.wallMs() / 200L);
        assertEquals(200_000_000L, snapshot.tickNanos());
        assertEquals(10_000L, snapshot.wallMs(), "held wall time is still elapsed time");
    }

    @Test
    void aWallRollbackNeverProducesANegativeDuration() {
        FakeClock clock = new FakeClock();
        ExecutionTiming timing = ExecutionTiming.started(clock);
        clock.advance(5_000L);
        timing.onTick(1_000L);

        clock.wall -= 60_000L;
        clock.nanos += 1_000_000L;

        ExecutionTiming.Snapshot snapshot = timing.snapshot();
        assertEquals(0L, snapshot.wallMs());
        assertTrue(timing.elapsedMs() >= 1L, "the monotonic source still measures");
        assertTrue(snapshot.tickNanos() >= 0L);
    }

    @Test
    void snapshotsNeverGoBackwards() {
        FakeClock clock = new FakeClock();
        ExecutionTiming timing = ExecutionTiming.started(clock);
        ExecutionTiming.Snapshot first = timing.snapshot();
        clock.advance(1_000L);
        timing.onTick(500L);
        ExecutionTiming.Snapshot second = timing.snapshot();
        timing.onHoldTick();
        ExecutionTiming.Snapshot third = timing.snapshot();

        assertTrue(second.wallMs() >= first.wallMs());
        assertTrue(third.wallMs() >= second.wallMs());
        assertEquals(1L, second.activeTicks());
        assertEquals(1L, third.heldTicks());
    }

    @Test
    void aNegativeProcessingMeasurementIsClamped() {
        ExecutionTiming timing = ExecutionTiming.started(new FakeClock());

        timing.onTick(-999L);

        assertEquals(1L, timing.snapshot().activeTicks());
        assertEquals(0L, timing.snapshot().tickNanos());
    }
}
