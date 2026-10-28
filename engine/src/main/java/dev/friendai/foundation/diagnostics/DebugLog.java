package dev.friendai.foundation.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
}
