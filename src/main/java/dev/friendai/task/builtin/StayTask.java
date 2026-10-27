package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.TaskStatus;

/**
 * Waits in place, but never forever: a stay has a tick budget and ends by itself, the way any other order does.
 *
 * <p>Waiting is not working, so the runner treats it as an idle moment for the queue: a job that becomes ready ends the
 * stay at once, and free time keeps its shift instead of reading the wait as owner work.
 */
public final class StayTask implements Task {
    private final int maxTicks;
    private int ticks;

    /** A stay of the configured length: the task timeout bounds every order, this one included. */
    public StayTask() {
        this(TaskSettings.stayTimeoutSec * 20);
    }

    public StayTask(int maxTicks) {
        this.maxTicks = Math.max(1, maxTicks);
    }

    @Override
    public String type() {
        return "stay";
    }

    @Override
    public void start(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        return ++ticks >= maxTicks ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return "staying in place";
    }

    @Override
    public String resultSummary() {
        return "stopped staying";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public String saveData() {
        return "stay";
    }
}
