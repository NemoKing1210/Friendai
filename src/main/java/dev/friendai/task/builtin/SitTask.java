package dev.friendai.task.builtin;

import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;

/** Sits down (a low crouch) and stays there until another task starts or the companion is told to stand. */
public final class SitTask implements Task {
    @Override
    public String type() {
        return "sit";
    }

    @Override
    public void start(TaskContext c) {
        c.companion().getNavigation().stop();
        c.companion().body().setSitting(true);
    }

    @Override
    public TaskStatus tick(TaskContext c) {
        if (!c.companion().body().sitting()) {
            return TaskStatus.SUCCESS;
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(TaskContext c) {
        c.companion().body().setSitting(false);
    }

    @Override
    public String describe() {
        return "sitting";
    }

    @Override
    public String resultSummary() {
        return "stood up";
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }
}
