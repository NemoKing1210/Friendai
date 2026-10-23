package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;

/** Stands still until told otherwise. */
public final class StayTask implements Task {
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
        return TaskStatus.RUNNING;
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
