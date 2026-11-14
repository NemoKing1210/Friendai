package dev.friendai.infrastructure.persistence;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coalesces repeated writes of one key onto a single writer: the newest value is the one that reaches the disk, and
 * at most one write per key waits for the executor at a time.
 *
 * <p>Callers capture their immutable value on the thread that owns the state and submit it here; the executor does the
 * I/O. Values submitted while a write is running are kept and drained afterwards, so a burst of snapshots costs one
 * write per drain instead of one per snapshot.
 *
 * <p>{@link #delete} runs the caller's delete while holding the key's write lock, so a value written after the delete
 * was requested cannot recreate what the delete removed. A failed write is counted and named by its exception type,
 * never by a path or a value; the next successful write clears the failure episode.
 *
 * <p>The slot of a key is removed from the map only while it is idle. Every operation re-reads the map once it holds
 * the lock and retries on the live slot if the map moved on, so a caller that raced the removal can never write
 * through an orphaned slot beside the new one; two drains of one key therefore cannot run at the same time.
 */
public final class LatestWriteQueue<K, V> {
    /** Writes one value. A failure is reported by throwing; the queue counts it and keeps the next value. */
    public interface Writer<V> {
        void write(V value);
    }

    private static final class Slot<V> {
        private V pending;
        private boolean scheduled;

        private boolean isEmpty() {
            return pending == null && !scheduled;
        }
    }

    private final Executor executor;
    private final Writer<V> writer;
    private final Map<K, Slot<V>> slots = new ConcurrentHashMap<>();
    private final AtomicLong writeFailures = new AtomicLong();
    private final AtomicLong consecutiveFailures = new AtomicLong();
    private final AtomicReference<String> lastFailure = new AtomicReference<>("");

    public LatestWriteQueue(Executor executor, Writer<V> writer) {
        this.executor = Objects.requireNonNull(executor);
        this.writer = Objects.requireNonNull(writer);
    }

    /**
     * Stores {@code value} as the newest value of {@code key} and schedules a drain unless one is already waiting.
     *
     * @return false when the executor refused the work; the value stays pending and a later submit retries it
     */
    public boolean submit(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        while (true) {
            Slot<V> slot = slots.computeIfAbsent(key, ignored -> new Slot<>());
            synchronized (slot) {
                if (slots.get(key) != slot) {
                    continue;
                }
                slot.pending = value;
                if (slot.scheduled) return true;
                slot.scheduled = true;
            }
            try {
                executor.execute(() -> drain(key, slot));
                return true;
            } catch (RejectedExecutionException refused) {
                synchronized (slot) {
                    slot.scheduled = false;
                }
                recordFailure(refused);
                return false;
            }
        }
    }

    /**
     * Writes {@code value} now on the calling thread, replacing any pending value and waiting for an in-flight write
     * of the same key. A terminal snapshot is written this way so an older queued one cannot follow it to the disk.
     * A failure is counted like any other and rethrown: the caller decides whether it can continue.
     */
    public void flush(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        while (true) {
            Slot<V> slot = slots.computeIfAbsent(key, ignored -> new Slot<>());
            synchronized (slot) {
                if (slots.get(key) != slot) {
                    continue;
                }
                slot.pending = null;
                try {
                    writer.write(value);
                    clearFailure();
                } catch (RuntimeException failed) {
                    recordFailure(failed);
                    throw failed;
                } finally {
                    if (slot.isEmpty() && !slot.scheduled) slots.remove(key, slot);
                }
                return;
            }
        }
    }

    /** Drops any value waiting for {@code key}, then runs {@code delete}. Serialized with an in-flight write. */
    public void delete(K key, Runnable delete) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(delete);
        while (true) {
            Slot<V> slot = slots.computeIfAbsent(key, ignored -> new Slot<>());
            synchronized (slot) {
                if (slots.get(key) != slot) {
                    continue;
                }
                slot.pending = null;
                delete.run();
                if (slot.isEmpty() && !slot.scheduled) slots.remove(key, slot);
                return;
            }
        }
    }

    /** Total failed writes since construction. */
    public long writeFailures() {
        return writeFailures.get();
    }

    /** The exception type of the last failed write, or an empty string after a success. Never a path or a value. */
    public String lastFailure() {
        return lastFailure.get();
    }

    /** Consecutive failed writes: a caller logs the first of an episode instead of every retry. */
    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** Whether nothing is waiting or running for {@code key}; for a caller that must know a record is settled. */
    public boolean idle(K key) {
        Slot<V> slot = slots.get(key);
        if (slot == null) return true;
        synchronized (slot) {
            return slot.isEmpty();
        }
    }

    private void drain(K key, Slot<V> slot) {
        while (true) {
            V value;
            synchronized (slot) {
                value = slot.pending;
                if (value == null) {
                    slot.scheduled = false;
                    if (slot.isEmpty()) slots.remove(key, slot);
                    return;
                }
                slot.pending = null;
                try {
                    writer.write(value);
                    clearFailure();
                } catch (RuntimeException failed) {
                    recordFailure(failed);
                }
            }
        }
    }

    private void recordFailure(Throwable failure) {
        writeFailures.incrementAndGet();
        consecutiveFailures.incrementAndGet();
        lastFailure.set(failure.getClass().getSimpleName());
    }

    private void clearFailure() {
        consecutiveFailures.set(0);
        lastFailure.set("");
    }
}
