package dev.friendai.server.execution;

import dev.friendai.execution.log.ExecutionTiming;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.execution.log.TaskLogEvents;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.foundation.diagnostics.DiagnosticClock;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
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
            ServerSettings settings,
            dev.friendai.task.ProgressWatch progress,
            LogContext logContext,
            long execution) {}

    public record Completion(ActiveTask active, TaskStatus status, String summary) {}

    private final IntSupplier timeoutSec;
    private ActiveTask current;
    private ActiveTask frozen;
    private int frozenElapsed;
    private int nextId = 1;
    private long nextExecution = 1;
    private int activeTicks;
    private Consumer<TaskLogEvent> diagnostics = event -> {};
    private final DiagnosticClock clock;
    private ExecutionTiming timing;

    public ActiveTaskController(IntSupplier timeoutSec) {
        this(timeoutSec, DiagnosticClock.system());
    }

    public ActiveTaskController(IntSupplier timeoutSec, DiagnosticClock clock) {
        this.timeoutSec = timeoutSec;
        this.clock = clock == null ? DiagnosticClock.system() : clock;
    }

    public int allocateId(boolean emitEvents) {
        return emitEvents ? nextId++ : 0;
    }

    /** A diagnostic execution identity for one activation, independent of the public task id. */
    public long allocateExecution() {
        return nextExecution++;
    }

    /** Installs the structured lifecycle publisher; the default drops nothing and logs nothing by itself. */
    public void setDiagnostics(Consumer<TaskLogEvent> diagnostics) {
        this.diagnostics = diagnostics == null ? event -> {} : diagnostics;
    }

    /** Starts a task whose prior runner policy has already settled any active task. */
    public int start(Task task, TaskStartContext start) {
        int id = start.id();
        long execution = allocateExecution();
        LogContext logContext = start.logContext().withExecution(execution, id, start.planId(), start.jobId());
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
                start.settings(),
                new dev.friendai.task.ProgressWatch(activeTicks),
                logContext,
                execution);
        current = active;
        timing = ExecutionTiming.started(clock);
        diagnostics.accept(
                TaskLogEvents.startEvent(logContext, start.toolName(), java.util.Map.of("origin", start.origin())));
        try {
            task.start(context(start.taskContext(), active));
        } catch (RuntimeException e) {
            if (current == active) current = null;
            release(task, context(start.taskContext(), active), "failed to start");
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
        long processingStart = clock.nanoTime();
        try {
            status = active.task().tick(activeContext);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} crashed", active.toolName(), e);
            status = TaskStatus.FAILED;
            summary = "task crashed: " + e.getClass().getSimpleName();
        }
        if (timing != null) timing.onTick(clock.nanoTime() - processingStart);
        if (current != active) return Optional.empty();
        if (status == TaskStatus.RUNNING) {
            int stallTicks = Math.max(20, active.timeoutSec() * 10);
            dev.friendai.task.ProgressWatch.Status progress = active.progress()
                    .observe(
                            active.task().progressStamp(),
                            active.task().waitingTicks(),
                            active.task().waitingLimitTicks(),
                            activeTicks,
                            stallTicks);
            if (progress == dev.friendai.task.ProgressWatch.Status.STALLED) {
                safeStop(active.task(), activeContext, "no progress");
                status = TaskStatus.FAILED;
                String phase = active.task().debugStatus();
                if (phase == null || phase.isBlank()) phase = active.task().describe();
                summary = "no progress for " + Math.max(1, stallTicks / 20) + "s: " + phase;
            }
        }
        if (status == TaskStatus.RUNNING
                && active.task().hasTimeout()
                && activeTicks - active.startActiveTick() > active.timeoutSec() * 20) {
            safeStop(active.task(), activeContext, "timed out");
            status = TaskStatus.FAILED;
            summary = "timed out";
        }
        if (status == TaskStatus.RUNNING) return Optional.empty();
        ExecutionTiming.Snapshot measured = timing == null ? null : timing.snapshot();
        diagnostics.accept(TaskLogEvents.finishEvent(
                active.logContext(),
                active.toolName(),
                status.name().toLowerCase(Locale.ROOT),
                measured == null ? -1L : measured.wallMs(),
                measured == null
                        ? java.util.Map.of()
                        : java.util.Map.of(
                                "activeTicks", Long.toString(measured.activeTicks()),
                                "heldTicks", Long.toString(measured.heldTicks()),
                                "tickNanos", Long.toString(measured.tickNanos()))));
        current = null;
        release(active.task(), activeContext, "terminal");
        return Optional.of(new Completion(active, status, summary));
    }

    /** Stops the current task and returns its metadata for queue settlement and event publication. */
    public Optional<ActiveTask> stop(StopRequest request, TaskContext taskContext) {
        ActiveTask active = current;
        if (active == null) return Optional.empty();
        diagnostics.accept(TaskLogEvents.cancelEvent(active.logContext(), active.toolName(), request.reason()));
        current = null;
        safeStop(active.task(), context(taskContext, active), request.reason());
        return Optional.of(active);
    }

    public Optional<ActiveTask> stopFrozen(StopRequest request, TaskContext taskContext) {
        ActiveTask active = frozen;
        if (active == null) return Optional.empty();
        diagnostics.accept(TaskLogEvents.cancelEvent(active.logContext(), active.toolName(), request.reason()));
        frozen = null;
        frozenElapsed = 0;
        safeStop(active.task(), context(taskContext, active), request.reason());
        return Optional.of(active);
    }

    public void freezeCurrent() {
        if (current == null) return;
        diagnostics.accept(TaskLogEvents.holdEvent(current.logContext(), current.toolName()));
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
                active.settings(),
                active.progress(),
                active.logContext(),
                active.execution());
        frozenElapsed = 0;
        diagnostics.accept(TaskLogEvents.resumeEvent(active.logContext(), active.toolName()));
    }

    public void clearFrozen() {
        frozen = null;
        frozenElapsed = 0;
    }

    /** One runner tick observed under an explicit hold; it does not advance the task's active work. */
    public void recordHoldTick() {
        if (timing != null) timing.onHoldTick();
    }

    public void reset() {
        current = null;
        clearFrozen();
        timing = null;
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
                base.companion(),
                base.level(),
                base.execution(),
                active.creativeActionContext(),
                active.settings(),
                active.logContext());
    }

    private void safeStop(Task task, TaskContext taskContext, String reason) {
        try {
            task.stop(taskContext);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to stop ({})", task.type(), reason, e);
        }
    }

    private void release(Task task, TaskContext taskContext, String when) {
        try {
            task.onTerminal(taskContext);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed terminal cleanup ({})", task.type(), when, e);
        }
    }
}
