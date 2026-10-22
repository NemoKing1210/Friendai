package dev.friedai.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/** Client-side accumulation of log entries received in snapshots. */
public final class LogBuffer {
    private final int capacity;
    private final Deque<LogEntry> entries = new ArrayDeque<>();
    private long lastId;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void append(List<LogEntry> incoming) {
        for (LogEntry e : incoming) {
            if (e.id() <= lastId) {
                continue;
            }
            lastId = e.id();
            entries.addLast(e);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    public synchronized List<LogEntry> view(Set<LogKind> kinds) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry e : entries) {
            if (kinds.contains(e.kind())) {
                result.add(e);
            }
        }
        return result;
    }

    public synchronized void clear() {
        entries.clear();
        lastId = 0;
    }
}
