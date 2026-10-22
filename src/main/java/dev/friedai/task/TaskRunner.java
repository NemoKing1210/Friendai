package dev.friedai.task;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.task.builtin.FollowTask;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one task at a time for a companion, with timeouts, crash isolation and a default activity. */
public final class TaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("friedai/task");

    private record Active(int id, String toolName, Task task, boolean emit, int startTick) {}

    private final CompanionEntity companion;
    private final IntSupplier timeoutSec;
    private final BooleanSupplier defaultFollow;

    private Active current;
    private int nextId = 1;
    private int ticks;
    private TaskListener listener;

    public TaskRunner(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow) {
        this.companion = companion;
        this.timeoutSec = timeoutSec;
        this.defaultFollow = defaultFollow;
    }

    public void setListener(TaskListener listener) {
        this.listener = listener;
    }

    public Optional<Task> current() {
        return Optional.ofNullable(current).map(Active::task);
    }

    /** Starts a task, cancelling the current one. Returns the task id. */
    public int start(Task task, String toolName) {
        return begin(task, toolName, task.emitsEvents());
    }

    /** Starts a task that was saved with the world; the brain is not told about it. */
    public void restore(Task task) {
        begin(task, task.type(), false);
    }

    /** Cancels the current task and falls back to the default activity. */
    public void stop(String reason) {
        if (current != null) {
            cancelCurrent(reason);
        }
        startDefault();
    }

    public void tick() {
        ticks++;
        if (current == null) {
            startDefault();
            return;
        }
        Active active = current;
        TaskContext ctx = context();
        TaskStatus status;
        String summary = null;
        try {
            status = active.task().tick(ctx);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} crashed", active.toolName(), e);
            status = TaskStatus.FAILED;
            summary = "task crashed: " + e.getClass().getSimpleName();
        }
        if (status == TaskStatus.RUNNING
                && active.task().hasTimeout()
                && ticks - active.startTick() > timeoutSec.getAsInt() * 20) {
            safeStop(active.task(), ctx);
            status = TaskStatus.FAILED;
            summary = "timed out";
        }
        if (status != TaskStatus.RUNNING) {
            current = null;
            if (active.emit() && listener != null) {
                listener.onFinished(active.id(), active.toolName(), status, summary != null ? summary : active.task().resultSummary());
            }
            startDefault();
        }
    }

    private int begin(Task task, String toolName, boolean emit) {
        if (current != null) {
            cancelCurrent("replaced by a new task");
        }
        Active active = new Active(nextId++, toolName, task, emit, ticks);
        current = active;
        try {
            task.start(context());
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to start", toolName, e);
            current = null;
            if (emit && listener != null) {
                listener.onFinished(active.id(), toolName, TaskStatus.FAILED, "task crashed: " + e.getClass().getSimpleName());
            }
        }
        return active.id();
    }

    private void cancelCurrent(String reason) {
        Active active = current;
        current = null;
        safeStop(active.task(), context());
        if (active.emit() && listener != null) {
            listener.onCancelled(active.id(), active.toolName(), reason);
        }
    }

    private void startDefault() {
        if (current == null && defaultFollow.getAsBoolean()) {
            begin(new FollowTask(true), "follow", false);
        }
    }

    private void safeStop(Task task, TaskContext ctx) {
        try {
            task.stop(ctx);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to stop", task.type(), e);
        }
    }

    private TaskContext context() {
        return new TaskContext(companion, (ServerLevel) companion.level());
    }
}
