package dev.friendai.server.execution;

import dev.friendai.execution.log.ExecutionTiming;
import dev.friendai.execution.log.TaskDiagnosticEpisode;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.execution.log.TaskLogEvents;
import dev.friendai.execution.log.TaskLogSpan;
import dev.friendai.execution.tool.Caller;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.foundation.diagnostics.DiagnosticClock;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskPhase;
import dev.friendai.task.TaskProgress;
import dev.friendai.task.TaskProgressThrottle;
import dev.friendai.task.TaskStatus;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.UnaryOperator;
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
            long execution,
            ExecutionTiming timing,
            TaskProgressThrottle progressThrottle,
            TaskDiagnosticEpisode episode,
            Spans spans) {}

    /** The bounded live-span state of one activation: depth, the next id and how many were truncated. */
    static final class Spans {
        int depth;
        long next = 1;
        int truncated;
    }

    public record Completion(ActiveTask active, TaskStatus status, String summary) {}

    /** A crash summary names the exception, not its arguments; its message part stays inside this bound. */
    static final int MAX_CRASH_MESSAGE_CHARS = 160;
    /** The raw message is scanned only up to this window, so a huge provider body cannot cost a crash a scan. */
    static final int MAX_CRASH_RAW_CHARS = 4096;

    private final IntSupplier timeoutSec;
    private UnaryOperator<String> redactor;
    private ActiveTask current;
    private ActiveTask frozen;
    private int frozenElapsed;
    private int nextId = 1;
    private long nextExecution = 1;
    private int activeTicks;
    private Consumer<TaskLogEvent> diagnostics = event -> {};
    private final DiagnosticClock clock;

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

    /**
     * Installs the diagnostics redactor applied to a crash message. The owning session supplies the gateway's redactor,
     * so the summary that reaches a prompt or a player is masked; null leaves the text unchanged. The summary never
     * carries a stack, and redaction happens before the message is shortened.
     */
    public void setRedactor(UnaryOperator<String> redactor) {
        this.redactor = redactor;
    }

    /** Starts a task whose prior runner policy has already settled any active task. */
    public int start(Task task, TaskStartContext start) {
        int id = start.id();
        long execution = allocateExecution();
        LogContext logContext = start.logContext().withExecution(execution, id, start.planId(), start.jobId());
        ExecutionTiming timing = ExecutionTiming.started(clock);
        TaskProgressThrottle progressThrottle = new TaskProgressThrottle();
        TaskDiagnosticEpisode episode = new TaskDiagnosticEpisode();
        Spans spans = new Spans();
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
                execution,
                timing,
                progressThrottle,
                episode,
                spans);
        current = active;
        diagnostics.accept(
                TaskLogEvents.startEvent(logContext, start.toolName(), java.util.Map.of("origin", start.origin())));
        try {
            task.start(context(start.taskContext(), active));
        } catch (RuntimeException e) {
            if (current == active) current = null;
            diagnostics.accept(TaskLogEvents.finishEvent(
                    logContext, start.toolName(), "failed", -1L, java.util.Map.of("reason", "start_failed")));
            release(task, context(start.taskContext(), active), "failed to start");
            throw e;
        }
        return active.id();
    }

    /** Ticks the current task and returns a terminal result after clearing its ownership. */
    public Optional<Completion> tick(TaskContext activeContext) {
        ActiveTask active = current;
        if (active == null) return Optional.empty();
        if (activeContext.logContext() != active.logContext()) activeContext = context(activeContext, active);
        activeTicks++;
        TaskStatus status;
        String summary = null;
        long processingStart = clock.nanoTime();
        try {
            status = active.task().tick(activeContext);
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Task {} ({}) crashed at active tick {}",
                    active.toolName(),
                    active.task().type(),
                    activeTicks,
                    e);
            status = TaskStatus.FAILED;
            summary = crashSummary(e);
        }
        active.timing().onTick(clock.nanoTime() - processingStart);
        if (current != active) return Optional.empty();
        if (status == TaskStatus.RUNNING) {
            int stallTicks = Math.max(20, active.timeoutSec() * 10);
            long stamp = active.task().progressStamp();
            int waitingTicks = active.task().waitingTicks();
            int waitingLimitTicks = active.task().waitingLimitTicks();
            dev.friendai.task.ProgressWatch.Status progress =
                    active.progress().observe(stamp, waitingTicks, waitingLimitTicks, activeTicks, stallTicks);
            if (progress == dev.friendai.task.ProgressWatch.Status.STALLED) {
                String detail = phaseOf(active);
                if (current != active) return Optional.empty();
                diagnostics.accept(TaskLogEvents.snapshotEvent(
                        active.logContext(),
                        active.toolName(),
                        "stalled",
                        snapshotFacts(active, stallTicks, stamp, detail)));
                safeStop(active.task(), activeContext, "no progress");
                if (current != active) return Optional.empty();
                status = TaskStatus.FAILED;
                summary = "no progress for " + Math.max(1, stallTicks / 20) + "s: " + detail;
            } else {
                reportRunningState(active, stamp, waitingTicks, waitingLimitTicks);
                if (current != active) return Optional.empty();
            }
        }

        if (status == TaskStatus.RUNNING
                && active.task().hasTimeout()
                && activeTicks - active.startActiveTick() > active.timeoutSec() * 20) {
            safeStop(active.task(), activeContext, "timed out");
            if (current != active) return Optional.empty();
            status = TaskStatus.FAILED;
            summary = "timed out";
        }
        if (status == TaskStatus.RUNNING) return Optional.empty();
        if (summary == null) summary = active.task().resultSummary();
        ExecutionTiming.Snapshot measured = active.timing().snapshot();
        diagnostics.accept(TaskLogEvents.finishEvent(
                active.logContext(),
                active.toolName(),
                status.name().toLowerCase(Locale.ROOT),
                measured.wallMs(),
                terminalFacts(active, measured, summary)));
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
        // The interrupt ran on this runner while the task was frozen; that time is not the task's stall time.
        active.progress().rebase(frozenElapsed);
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
                active.execution(),
                active.timing(),
                active.progressThrottle(),
                active.episode(),
                active.spans());
        frozenElapsed = 0;
        diagnostics.accept(TaskLogEvents.resumeEvent(active.logContext(), active.toolName()));
    }

    public void clearFrozen() {
        frozen = null;
        frozenElapsed = 0;
    }

    /** One runner tick observed under an explicit hold; it does not advance the task's active work. */
    public void recordHoldTick() {
        ActiveTask active = current != null ? current : frozen;
        if (active != null) active.timing().onHoldTick();
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

    /** The live span depth one activation may open; deeper activity becomes counted opaque detail. */
    public static final int MAX_SPAN_DEPTH = 8;

    /** Opens a nested span for the current activation, or a detached one when depth is exhausted. */
    public TaskLogSpan beginSpan(LogContext parent, String operation, String reason, Map<String, String> facts) {
        ActiveTask active = current;
        if (active == null) return TaskLogSpan.none(operation);
        Spans spans = active.spans();
        if (spans.depth >= MAX_SPAN_DEPTH) {
            spans.truncated++;
            return TaskLogSpan.none(operation);
        }
        long spanId = spans.next++;
        spans.depth++;
        LogContext base = parent == null ? LogContext.NONE : parent;
        LogContext child = base.withSpan(spanId, base.span(), 0L);
        TaskLogSpan.Sink sink = new TaskLogSpan.Sink() {
            @Override
            public void start(TaskLogSpan span) {
                diagnostics.accept(
                        TaskLogEvents.spanStart(span.context(), span.operation(), span.reason(), span.startFacts()));
            }

            @Override
            public void end(TaskLogSpan span, String status, String reasonText, Map<String, String> endFacts) {
                spans.depth = Math.max(0, spans.depth - 1);
                diagnostics.accept(
                        TaskLogEvents.spanEnd(span.context(), span.operation(), status, reasonText, endFacts));
            }
        };
        return new TaskLogSpan(child, operation, reason, facts, sink).start();
    }

    /** Publishes one decision fact for the current activation; called on transitions, not every tick. */
    public boolean reportDecision(String event, String reason, Map<String, String> facts) {
        ActiveTask active = current;
        if (active == null || event == null || event.isBlank()) return false;
        diagnostics.accept(TaskLogEvents.decisionEvent(active.logContext(), event, active.toolName(), reason, facts));
        return true;
    }

    /** Publishes a throttled phase fact for the current activation; false when it says nothing new. */
    public boolean reportPhase(TaskPhase phase, int percent, String reason, String detail, long stamp) {
        ActiveTask active = current;
        if (active == null || phase == null) return false;
        TaskProgress event = active.progressThrottle()
                .observe(
                        active.id(),
                        active.toolName(),
                        phase,
                        percent,
                        detail,
                        stamp,
                        activeTicks - active.startActiveTick());
        if (event == null) return false;
        diagnostics.accept(TaskLogEvents.phaseEvent(
                active.logContext(), active.toolName(), event.phase(), event.percent(), reason, event.detail()));
        return true;
    }

    private void reportRunningState(ActiveTask active, long stamp, int waitingTicks, int waitingLimitTicks) {
        int localTick = activeTicks - active.startActiveTick();
        boolean waiting = waitingTicks >= 0;
        TaskPhase phase = waiting ? TaskPhase.WAITING : TaskPhase.PROGRESS;
        String label = waiting ? "waiting" : "running";
        boolean phaseDue = active.progressThrottle().due(active.id(), phase, stamp, localTick);
        boolean sampleDue = active.episode().sample(localTick, stamp, waiting, label);
        if (!phaseDue && !sampleDue) return;
        String detail = phaseOf(active);
        if (current != active) return;
        Map<String, String> facts = Map.of(
                "waitingTicks", Integer.toString(waitingTicks),
                "waitingLimitTicks", Integer.toString(waitingLimitTicks),
                "stamp", Long.toString(stamp));
        if (phaseDue) {
            active.progressThrottle().observe(active.id(), active.toolName(), phase, -1, detail, stamp, localTick);
        }
        if (!phaseDue) active.progressThrottle().recordSample(localTick);
        TaskLogEvent event = TaskLogEvents.phaseEvent(
                active.logContext(),
                active.toolName(),
                phase,
                -1,
                phaseDue ? label : waiting ? "waiting" : "no_progress",
                detail,
                facts);
        if (!phaseDue && waiting) {
            event = new TaskLogEvent(
                    event.context(),
                    event.parent(),
                    dev.friendai.foundation.diagnostics.LogEvents.TASK_PROGRESS,
                    event.tool(),
                    event.status(),
                    event.durationMs(),
                    event.facts());
        }
        diagnostics.accept(event);
    }

    private static Map<String, String> terminalFacts(
            ActiveTask active, ExecutionTiming.Snapshot measured, String summary) {
        Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("activeTicks", Long.toString(measured.activeTicks()));
        facts.put("heldTicks", Long.toString(measured.heldTicks()));
        facts.put("tickNanos", Long.toString(measured.tickNanos()));
        facts.put("summary", summary == null ? "" : summary);
        if (active.spans().truncated > 0) {
            facts.put("truncatedSpans", Integer.toString(active.spans().truncated));
        }
        return facts;
    }

    /** Everything a pre-stop snapshot needs, collected before a stop/cleanup can clear the task. */
    private java.util.Map<String, String> snapshotFacts(ActiveTask active, int stallTicks, long stamp, String detail) {
        ExecutionTiming.Snapshot measured = active.timing().snapshot();
        java.util.Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("reason", "stalled");
        facts.put("phase", detail);
        facts.put("ticksSinceChange", Integer.toString(active.progress().ticksSinceChange(activeTicks)));
        facts.put("activeTicks", Long.toString(measured.activeTicks()));
        facts.put("heldTicks", Long.toString(measured.heldTicks()));
        facts.put("tickNanos", Long.toString(measured.tickNanos()));
        facts.put("stallTicks", Integer.toString(stallTicks));
        facts.put("timeoutSec", Integer.toString(active.timeoutSec()));
        facts.put("stamp", Long.toString(stamp));
        return facts;
    }

    private static String phaseOf(ActiveTask active) {
        String phase = active.task().debugStatus();
        if (phase == null || phase.isBlank()) phase = active.task().describe();
        return phase == null ? "" : phase;
    }

    private TaskContext context(TaskContext base, ActiveTask active) {
        return new TaskContext(
                base.companion(),
                base.level(),
                base.execution(),
                active.creativeActionContext(),
                active.settings(),
                active.logContext(),
                base.structureBuilder());
    }

    /**
     * The exception a task threw, named and with its masked message; never a stack, an argument list or a path. The
     * package-private shape is shared with the restore and activation paths so one failure phrase has one form.
     */
    String crashSummary(RuntimeException crash) {
        String type = crash.getClass().getSimpleName();
        if (type.isBlank()) type = crash.getClass().getName();
        String detail;
        try {
            detail = crashDetail(crash.getMessage(), redactor);
        } catch (RuntimeException redactionFailed) {
            // The crash handler must not throw; a failing redactor degrades to the class alone.
            detail = "no message";
        }
        return "task crashed: " + type + ": " + detail;
    }

    /**
     * A message that is safe to show: bounded before the regex runs, masked before whitespace is collapsed (a
     * configured secret may contain a line break), then cut to one prompt-sized line.
     */
    private static String crashDetail(String message, UnaryOperator<String> redactor) {
        if (message == null) return "no message";
        String window = bound(message, MAX_CRASH_RAW_CHARS);
        if (window.isBlank()) return "no message";
        String masked = redactor == null ? window : redactor.apply(window);
        String single = masked.strip().replaceAll("\\s+", " ");
        return single.isBlank() ? "no message" : bound(single, MAX_CRASH_MESSAGE_CHARS);
    }

    /** Cuts on a code-point boundary so an emoji or another surrogate pair is never left half-written. */
    private static String bound(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
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
