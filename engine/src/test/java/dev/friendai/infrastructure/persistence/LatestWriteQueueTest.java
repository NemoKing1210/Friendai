package dev.friendai.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class LatestWriteQueueTest {
    /** Runs submitted work only when the test asks, so the coalescing can be observed without sleeps. */
    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean refusing;

        @Override
        public void execute(Runnable command) {
            if (refusing) throw new RejectedExecutionException("full");
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }
    }

    private final ManualExecutor executor = new ManualExecutor();
    private final List<String> written = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final LatestWriteQueue<String, String> queue =
            new LatestWriteQueue<>(executor, value -> written.add(value));

    @Test
    void aBurstOfSubmitsWritesOnlyTheNewestValue() {
        queue.submit("build", "one");
        queue.submit("build", "two");
        queue.submit("build", "three");

        executor.runAll();

        assertEquals(List.of("three"), written);
    }

    @Test
    void valuesSubmittedWhileWritingDrainAfterwards() {
        queue.submit("build", "first");
        executor.runAll();
        queue.submit("build", "second");
        executor.runAll();

        assertEquals(List.of("first", "second"), written);
    }

    @Test
    void deleteDropsAPendingValueAndRunsAfterAnyWrite() {
        queue.submit("build", "snapshot");
        queue.delete("build", () -> deleted.add("removed"));
        executor.runAll();

        assertTrue(written.isEmpty(), "a value deleted before its write must not reach the disk");
        assertEquals(List.of("removed"), deleted);
        assertTrue(queue.idle("build"));
    }

    @Test
    void deleteRunsAfterAnInFlightWrite() {
        queue.submit("build", "snapshot");
        executor.runAll();
        queue.delete("build", () -> deleted.add("removed"));

        assertEquals(List.of("snapshot"), written);
        assertEquals(List.of("removed"), deleted);
    }

    @Test
    void aFailedWriteIsCountedByNameAndASuccessClearsTheEpisode() {
        LatestWriteQueue<String, String> failing = new LatestWriteQueue<>(executor, value -> {
            if (value.equals("bad")) throw new IllegalStateException("disk full");
            written.add(value);
        });

        failing.submit("build", "bad");
        executor.runAll();
        assertEquals(1, failing.writeFailures());
        assertEquals(1, failing.consecutiveFailures());
        assertEquals("IllegalStateException", failing.lastFailure());

        failing.submit("build", "good");
        executor.runAll();
        assertEquals(1, failing.writeFailures());
        assertEquals(0, failing.consecutiveFailures());
        assertEquals("", failing.lastFailure());
        assertEquals(List.of("good"), written);
    }

    @Test
    void flushWritesNowAndSupersedesAPendingValue() {
        queue.submit("build", "queued");
        queue.flush("build", "terminal");

        assertEquals(List.of("terminal"), written);

        executor.runAll();
        assertEquals(List.of("terminal"), written, "the superseded value must not reach the disk afterwards");
        assertTrue(queue.idle("build"));
    }

    @Test
    void aFailedFlushIsCountedAndRethrown() {
        LatestWriteQueue<String, String> failing = new LatestWriteQueue<>(executor, value -> {
            throw new IllegalStateException("disk full");
        });

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> failing.flush("build", "terminal"));
        assertEquals(1, failing.writeFailures());
        assertEquals("IllegalStateException", failing.lastFailure());
    }

    @Test
    void aRefusedSubmitKeepsTheValueForTheNextAttempt() {
        executor.refusing = true;
        assertFalse(queue.submit("build", "snapshot"));
        assertEquals(1, queue.writeFailures());
        assertFalse(queue.idle("build"));

        executor.refusing = false;
        assertTrue(queue.submit("build", "snapshot"));
        executor.runAll();
        assertEquals(List.of("snapshot"), written);
    }
}
