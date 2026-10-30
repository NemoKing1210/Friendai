package dev.friendai.foundation.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/** Bounded log of what the brain did. Text is redacted, then truncated. */
public final class DebugLog {
    private final int capacity;
    private final int maxTextChars;
    private final UnaryOperator<String> redactor;
    private final LongSupplier clock;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long nextId = 1;

    /** One bounded read of the buffer; ids on both ends are exclusive and {@code limit} is applied after filtering. */
    public record Query(Set<LogKind> kinds, long afterId, long beforeId, int limit, boolean newestFirst) {
        public Query {
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        }
    }

    public DebugLog(int capacity, int maxTextChars, UnaryOperator<String> redactor, LongSupplier clock) {
        this.capacity = capacity;
        this.maxTextChars = maxTextChars;
        this.redactor = redactor;
        this.clock = clock;
    }

    public synchronized LogEntry add(LogKind kind, String text) {
        String clean = redactor.apply(text == null ? "" : text);
        if (clean.length() > maxTextChars) {
            clean = clean.substring(0, maxTextChars) + "…";
        }
        LogEntry entry = new LogEntry(nextId++, clock.getAsLong(), kind, clean);
        entries.addLast(entry);
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
        return entry;
    }

    /** Entries with an id greater than {@code id}, oldest first; the last {@code limit} if there are more. */
    public synchronized List<LogEntry> after(long id, int limit) {
        List<LogEntry> newer = new ArrayList<>();
        for (LogEntry e : entries) {
            if (e.id() > id) {
                newer.add(e);
            }
        }
        return newer.size() <= limit ? newer : new ArrayList<>(newer.subList(newer.size() - limit, newer.size()));
    }

    public synchronized long lastId() {
        return nextId - 1;
    }

    /** A filtered window; when there are more matches than {@code limit}, the end named by {@code newestFirst} wins. */
    public synchronized List<LogEntry> query(Query query) {
        if (query == null || query.limit() < 1) return List.of();
        List<LogEntry> matching = new ArrayList<>();
        for (LogEntry entry : entries) {
            if (query.afterId() > 0 && entry.id() <= query.afterId()) continue;
            if (query.beforeId() > 0 && entry.id() >= query.beforeId()) continue;
            if (!query.kinds().isEmpty() && !query.kinds().contains(entry.kind())) continue;
            matching.add(entry);
        }
        if (matching.size() > query.limit()) {
            matching = query.newestFirst()
                    ? new ArrayList<>(matching.subList(matching.size() - query.limit(), matching.size()))
                    : new ArrayList<>(matching.subList(0, query.limit()));
        }
        if (query.newestFirst()) java.util.Collections.reverse(matching);
        return matching;
    }

    /** The oldest id still buffered, or the next id when the buffer is empty. */
    public synchronized long firstId() {
        LogEntry first = entries.peekFirst();
        return first == null ? nextId : first.id();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int capacity() {
        return capacity;
    }
}
