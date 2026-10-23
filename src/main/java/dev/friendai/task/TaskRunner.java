package dev.friendai.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.task.builtin.FollowTask;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one task at a time for a companion, with timeouts, crash isolation and a default activity. */
public final class TaskRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");

    private record Active(int id, String toolName, Task task, boolean emit, int startActiveTick) {}

    private final CompanionEntity companion;
    private final IntSupplier timeoutSec;
    private final BooleanSupplier defaultFollow;

    private Active current;
    private int nextId = 1;
    /** Advances only while the runner is unpaused: the timeout clock of the active task. */
    private int activeTicks;
    private TaskListener listener;
    private boolean paused;

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused;
    }

    public TaskRunner(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow) {
        this.companion = companion;
        this.timeoutSec = timeoutSec;
        this.defaultFollow = defaultFollow;
    }

    public void setListener(TaskListener listener) {
        this.listener = listener;
    }

    /** Passes a world notice to the listener, when one is attached. */
    public void notice(String text) {
        if (listener != null) {
            listener.onNotice(text);
        }
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

    /**
     * Cancels the current task only when {@code name} is the tool it was started under or the task says it matches,
     * then falls back to the default activity.
     *
     * @return the stopped task's description, or empty when nothing matched and nothing was stopped
     */
    public Optional<String> stopNamed(String name, String reason) {
        Active active = current;
        if (active == null || !(active.toolName().equals(name) || active.task().matches(name))) {
            return Optional.empty();
        }
        String description = active.task().describe();
        stop(reason);
        return Optional.of(description);
    }

    public void tick() {
        if (paused) {
            return;
        }
        if (current == null) {
            startDefault();
            return;
        }
        activeTicks++;
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
                && activeTicks - active.startActiveTick() > timeoutSec.getAsInt() * 20) {
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
        // Only tasks the brain hears about get a number, so "task #1" is the first thing the LLM started.
        Active active = new Active(emit ? nextId++ : 0, toolName, task, emit, activeTicks);
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
        if (!paused && current == null && defaultFollow.getAsBoolean()) {
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
        return new TaskContext(companion, (ServerLevel) companion.level(), this);
    }
}
