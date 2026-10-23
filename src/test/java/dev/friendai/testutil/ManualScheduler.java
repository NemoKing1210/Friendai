package dev.friendai.testutil;

import dev.friendai.brain.BrainScheduler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Runs {@code execute} immediately; delayed work runs when time is advanced. */
public final class ManualScheduler implements BrainScheduler {
    private record Delayed(long due, Runnable task) {}

    private final List<Delayed> delayed = new ArrayList<>();
    private long now;

    @Override
    public void execute(Runnable r) {
        r.run();
    }

    @Override
    public void schedule(Runnable r, long delayMs) {
        delayed.add(new Delayed(now + delayMs, r));
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advance(long ms) {
        now += ms;
        List<Delayed> due = delayed.stream()
                .filter(d -> d.due() <= now)
                .sorted(Comparator.comparingLong(Delayed::due))
                .toList();
        delayed.removeAll(due);
        due.forEach(d -> d.task().run());
    }
}
