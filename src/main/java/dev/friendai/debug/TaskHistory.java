package dev.friendai.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class TaskHistory {
    private final int capacity;
    private final Deque<TaskRecord> records = new ArrayDeque<>();

    public TaskHistory(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(TaskRecord record) {
        records.addFirst(record);
        while (records.size() > capacity) {
            records.removeLast();
        }
    }

    /** Newest first. */
    public synchronized List<TaskRecord> recent() {
        return new ArrayList<>(records);
    }
}
