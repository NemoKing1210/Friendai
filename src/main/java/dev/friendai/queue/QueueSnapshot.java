package dev.friendai.queue;

import java.util.List;
import java.util.Map;

public record QueueSnapshot(
        List<Job> running,
        List<Job> ready,
        List<Job> waiting,
        Map<Long, String> waitingReasons,
        List<PlanArchive> archives) {
    public QueueSnapshot {
        running = List.copyOf(running);
        ready = List.copyOf(ready);
        waiting = List.copyOf(waiting);
        waitingReasons = Map.copyOf(waitingReasons);
        archives = List.copyOf(archives);
    }

    public int pending() {
        return ready.size() + waiting.size();
    }
}
