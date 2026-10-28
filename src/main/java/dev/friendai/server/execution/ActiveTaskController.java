package dev.friendai.server.execution;

import dev.friendai.creative.CreativeActionContext;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.tool.Caller;
import java.util.Optional;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns one active task, its suspended queue task, and task-local lifecycle accounting. */
public final class ActiveTaskController {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");

    public record ActiveTask(
            int id,
            long jobId,
            long planId,
            String toolName,
            Task task,
            Caller caller,
            boolean emit,
            boolean defaultActivity,
            boolean interrupt,
            int startActiveTick,
            CreativeActionContext creativeActionContext,
            boolean creativeSensitive,
            int timeoutSec,
            ServerSettings settings) {}

    public record Completion(ActiveTask active, TaskStatus status, String summary) {}

    private final IntSupplier timeoutSec;
    private ActiveTask current;
    private ActiveTask frozen;
    private int frozenElapsed;
    private int nextId = 1;
    private int activeTicks;

    public ActiveTaskController(IntSupplier timeoutSec) {
        this.timeoutSec = timeoutSec;
    }

    public int allocateId(boolean emitEvents) {
        return emitEvents ? nextId++ : 0;
    }

    /** Starts a task whose prior runner policy has already settled any active task. */
    public int start(Task task, TaskStartContext start) {
        int id = start.id();
        ActiveTask active = new ActiveTask(
                id,
                start.jobId(),
                start.planId(),
                start.toolName(),
                task,
                start.caller(),
                start.emitEvents(),
                start.defaultActivity(),
                start.interrupt(),
                activeTicks,
                start.creativeActionContext(),
                start.creativeSensitive(),
                timeoutSec.getAsInt(),
                start.settings());
        current = active;
        try {
            task.start(context(start.taskContext(), active));
        } catch (RuntimeException e) {
            if (current == active) current = null;
            throw e;
        }
        return active.id();
    }

    /** Ticks the current task and returns a terminal result after clearing its ownership. */
    public Optional<Completion> tick(TaskContext activeContext) {
        ActiveTask active = current;
        if (active == null) return Optional.empty();
        activeTicks++;
        TaskStatus status;
        String summary = null;
        try {
            status = active.task().tick(activeContext);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} crashed", active.toolName(), e);
            status = TaskStatus.FAILED;
            summary = "task crashed: " + e.getClass().getSimpleName();
        }
        if (current != active) return Optional.empty();
        if (status == TaskStatus.RUNNING
                && active.task().hasTimeout()
                && activeTicks - active.startActiveTick() > active.timeoutSec() * 20) {
            safeStop(active.task(), activeContext, "timed out");
            status = TaskStatus.FAILED;
            summary = "timed out";
        }
        if (status == TaskStatus.RUNNING) return Optional.empty();
        current = null;
        return Optional.of(new Completion(active, status, summary));
    }

    /** Stops the current task and returns its metadata for queue settlement and event publication. */
    public Optional<ActiveTask> stop(StopRequest request, TaskContext taskContext) {
        ActiveTask active = current;
        if (active == null) return Optional.empty();
        current = null;
        safeStop(active.task(), context(taskContext, active), request.reason());
        return Optional.of(active);
    }

    public Optional<ActiveTask> stopFrozen(StopRequest request, TaskContext taskContext) {
        ActiveTask active = frozen;
        if (active == null) return Optional.empty();
        frozen = null;
        frozenElapsed = 0;
        safeStop(active.task(), context(taskContext, active), request.reason());
        return Optional.of(active);
    }

    public void freezeCurrent() {
        if (current == null) return;
        frozen = current;
        frozenElapsed = Math.max(0, activeTicks - current.startActiveTick());
        current = null;
    }

    public Optional<ActiveTask> takeFrozen() {
        ActiveTask active = frozen;
        frozen = null;
        return Optional.ofNullable(active);
    }

    public void resumeFrozen(ActiveTask active) {
        if (active == null) return;
        current = new ActiveTask(
                active.id(),
                active.jobId(),
                active.planId(),
                active.toolName(),
                active.task(),
                active.caller(),
                active.emit(),
                false,
                false,
                activeTicks - frozenElapsed,
                active.creativeActionContext(),
                active.creativeSensitive(),
                active.timeoutSec(),
                active.settings());
        frozenElapsed = 0;
    }

    public void clearFrozen() {
        frozen = null;
        frozenElapsed = 0;
    }

    public void reset() {
        current = null;
        clearFrozen();
        activeTicks = 0;
    }

    public Optional<Task> current() {
        return Optional.ofNullable(current).map(ActiveTask::task);
    }

    public ActiveTask currentActive() {
        return current;
    }

    public ActiveTask frozenActive() {
        return frozen;
    }

    public int activeTicks() {
        return activeTicks;
    }

    private TaskContext context(TaskContext base, ActiveTask active) {
        return new TaskContext(
                base.companion(), base.level(), base.execution(), active.creativeActionContext(), active.settings());
    }

    private void safeStop(Task task, TaskContext taskContext, String reason) {
        try {
            task.stop(taskContext);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to stop ({})", task.type(), reason, e);
        }
    }
}
