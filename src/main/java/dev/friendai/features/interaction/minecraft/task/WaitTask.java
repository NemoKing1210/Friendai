package dev.friendai.features.interaction.minecraft.task;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;

/** Stands still for a number of ticks, then finishes; a pause between the steps of a queue. */
public final class WaitTask implements Task {
    private final int ticks;
    private int elapsed;

    public WaitTask(int ticks) {
        this.ticks = ticks;
    }

    @Override
    public String type() {
        return "wait";
    }

    @Override
    public void start(TaskContext c) {
        c.companion().getNavigation().stop();
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        return ++elapsed >= ticks ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {}

    @Override
    public String describe() {
        return "waiting " + ticks / 20.0 + " seconds";
    }

    @Override
    public String resultSummary() {
        return "waited " + ticks / 20.0 + " seconds";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }
}
