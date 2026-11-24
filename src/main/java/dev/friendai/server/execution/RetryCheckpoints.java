package dev.friendai.server.execution;

import dev.friendai.task.TaskState;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The progress a failed attempt of a queued job left, kept until that job runs again.
 *
 * <p>Bounded and keyed by job id, with the job's arguments as an identity check: editing a job's arguments drops
 * what its previous attempt saved, because the checkpoint describes the old request. A checkpoint exists only after
 * the task committed part of its work, so keeping it is what lets a retry reconcile instead of repeating.
 */
public final class RetryCheckpoints {
    public static final int MAX_ENTRIES = 16;
    public static final int MAX_ARGS_CHARS = 8_192;

    /**
     * One job's saved progress and the arguments it belongs to. A {@code null} state marks an attempt that must not
     * be repeated at all — a crash may have changed the world before it recorded anything — so its job waits for the
     * owner instead of starting over.
     */
    public record Entry(String args, TaskState state) {
        public Entry {
            args = args == null ? "" : args;
            if (args.length() > MAX_ARGS_CHARS) throw new IllegalArgumentException("retry args are too long");
        }

        public boolean resumable() {
            return state != null;
        }
    }

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();

    /** Keeps the progress for the job; the least recently kept entry goes when the bound is reached. */
    public void keep(long jobId, String args, TaskState state) {
        if (jobId <= 0) return;
        if (args != null && args.length() > MAX_ARGS_CHARS) return;
        entries.remove(jobId);
        entries.put(jobId, new Entry(args, state));
        while (entries.size() > MAX_ENTRIES) {
            Iterator<Long> oldest = entries.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public Optional<Entry> peek(long jobId) {
        return Optional.ofNullable(entries.get(jobId));
    }

    /** Consumes the entry when the job's arguments still match; any entry is removed either way. */
    public Optional<Entry> take(long jobId, String args) {
        Entry entry = entries.remove(jobId);
        if (entry == null || !entry.args().equals(args == null ? "" : args)) return Optional.empty();
        return Optional.of(entry);
    }

    public void drop(long jobId) {
        entries.remove(jobId);
    }

    /** Moves a remembered job's progress to the job a retry queued in its place. */
    public boolean carry(long fromJobId, long toJobId, String args) {
        Entry entry = entries.remove(fromJobId);
        if (entry == null || toJobId <= 0) return false;
        keep(toJobId, args == null ? entry.args() : args, entry.state());
        return true;
    }

    /** Takes over a saved set, keeping the same bound; anything beyond it is dropped oldest first. */
    public void restore(Map<Long, Entry> saved) {
        entries.clear();
        if (saved == null) return;
        saved.forEach((jobId, entry) -> keep(jobId, entry.args(), entry.state()));
    }

    /** Drops progress for jobs that are no longer in the queue. */
    public void retainOnly(Collection<Long> jobIds) {
        entries.keySet().removeIf(jobId -> !jobIds.contains(jobId));
    }

    public void clear() {
        entries.clear();
    }

    /** An immutable copy, for saving and tests. */
    public Map<Long, Entry> snapshot() {
        return Map.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
