package dev.friendai.task;

import dev.friendai.core.CreativeModeService;
import dev.friendai.creative.CreativeAction;
import dev.friendai.creative.CreativeActionContext;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.movement.MovementOrders;
import dev.friendai.place.PlaceCandidate;
import dev.friendai.queue.FailurePolicy;
import dev.friendai.queue.GuardAction;
import dev.friendai.queue.GuardFacts;
import dev.friendai.queue.Job;
import dev.friendai.queue.JobQueue;
import dev.friendai.queue.JobState;
import dev.friendai.queue.PlanCursor;
import dev.friendai.queue.PlanDefinition;
import dev.friendai.queue.PlanInterpreter;
import dev.friendai.queue.PlanNode;
import dev.friendai.queue.PlanOptions;
import dev.friendai.queue.PlanRecord;
import dev.friendai.queue.PlanStatus;
import dev.friendai.queue.QueuePolicy;
import dev.friendai.queue.QueueSave;
import dev.friendai.queue.QueueText;
import dev.friendai.queue.QueuedStep;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.execution.ActiveTaskController;
import dev.friendai.server.execution.ActiveTaskController.ActiveTask;
import dev.friendai.server.execution.StopRequest;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.TaskStartContext;
import dev.friendai.task.builtin.FollowTask;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.plan.StepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import dev.friendai.tool.Caller;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one task at a time and schedules durable jobs when no urgent or legacy task owns the runner. */
public final class TaskRunner {
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");

    private final CompanionEntity companion;
    private final IntSupplier timeoutSec;
    private final BooleanSupplier defaultFollow;
    private final Supplier<ServerSettings> settingsSupplier;
    private final ActiveTaskController activeTasks;
    /** Whether the idle fallback may walk to the owner; free time narrows it (see DefaultActivityPolicy). */
    private final BooleanSupplier mayFollowOwner;

    private JobQueue queue;
    private ServerSettings queueSettings;
    private final Map<Long, StepFactory> factories = new HashMap<>();
    private final Map<Long, Map<String, PlanStepFactory>> structuredFactories = new HashMap<>();
    private final Map<Long, String> blockedPlans = new HashMap<>();
    /**
     * Plans whose remaining steps wait for a decision (a failed step, a missing tool result). The companion stands
     * and reports them instead of walking to the owner: a follow would preempt the work the failure is about.
     */
    private final java.util.Set<Long> awaitingDecision = new java.util.HashSet<>();
    /** A movement order is a task without a job behind it, so waiting in place never pins the queue. */
    private static boolean movementOrder(ActiveTask active) {
        return active != null && active.jobId() == 0 && MovementOrders.isOrder(active.toolName());
    }

    /** Queue plans that free time started; their jobs are autonomous work. */
    private final java.util.Set<Long> autonomousPlans = new java.util.HashSet<>();

    private long blockedSince = -1;
    private long nextPoll;
    private CompanionGuardFacts sharedFacts;
    private TaskEventSink eventSink;
    private final List<String> pendingNotices = new java.util.ArrayList<>();
    private final ArrayDeque<java.util.function.Consumer<TaskEventSink>> pendingEvents = new ArrayDeque<>();
    private int transitionDepth;
    private boolean flushingEvents;
    private boolean paused;
    private boolean suspended;
    private int holds;

    public TaskRunner(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow) {
        this(companion, timeoutSec, defaultFollow, () -> true, companion::serverSettings);
    }

    /** A runner that knows whether following is its business at all; the real entity passes the free-time gate. */
    public TaskRunner(
            CompanionEntity companion,
            IntSupplier timeoutSec,
            BooleanSupplier defaultFollow,
            BooleanSupplier mayFollowOwner) {
        this(companion, timeoutSec, defaultFollow, mayFollowOwner, companion::serverSettings);
    }

    public TaskRunner(
            CompanionEntity companion,
            IntSupplier timeoutSec,
            BooleanSupplier defaultFollow,
            BooleanSupplier mayFollowOwner,
            Supplier<ServerSettings> settingsSupplier) {
        this.companion = companion;
        this.timeoutSec = timeoutSec;
        this.defaultFollow = defaultFollow;
        this.mayFollowOwner = mayFollowOwner;
        this.settingsSupplier = settingsSupplier;
        this.activeTasks = new ActiveTaskController(timeoutSec);
        this.queueSettings = ServerSettings.DEFAULTS;
        this.queue = new JobQueue(queueSettings.queueLimits());
    }

    public void applySettings(ServerSettings settings) {
        if (settings == null || queueSettings == settings) return;
        queue.setLimits(settings.queueLimits());
        queueSettings = settings;
    }

    private void syncSettings() {
        applySettings(settingsSupplier.get());
    }

    public void setListener(TaskListener listener) {
        this.eventSink = listener;
        if (listener != null) {
            for (String notice : pendingNotices) emit(sink -> sink.onNotice(notice));
            pendingNotices.clear();
        }
    }

    public void setEventSink(TaskEventSink eventSink) {
        this.eventSink = eventSink;
        if (eventSink != null) {
            for (String notice : pendingNotices) emit(sink -> sink.onNotice(notice));
            pendingNotices.clear();
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused || holds > 0;
    }

    public void suspend() {
        suspended = true;
    }

    public void resume() {
        suspended = false;
    }

    public boolean suspended() {
        return suspended;
    }

    public void hold() {
        holds++;
    }

    public void release() {
        if (holds > 0) holds--;
    }

    public boolean held() {
        return holds > 0;
    }

    public void notice(String text) {
        if (eventSink != null) emit(sink -> sink.onNotice(text));
        else if (text != null) {
            if (pendingNotices.size() == 16) pendingNotices.removeFirst();
            pendingNotices.add(text);
        }
    }

    public void debug(String text) {
        if (eventSink != null) emit(sink -> sink.onDebug(text));
    }

    public Optional<Task> current() {
        return activeTasks.current();
    }
    /** Current task context for adapters such as movement controllers that tick below the Task boundary. */
    public TaskContext activeTaskContext() {
        return context();
    }

    public boolean currentIsDefaultActivity() {
        return activeTask() != null && activeTask().defaultActivity();
    }

    public boolean currentCreativeActionAllowed(CreativeAction action) {
        return activeTask() != null
                && CreativeModeService.isActionAuthorized(
                        companion, activeTask().creativeActionContext(), action);
    }

    public int currentId() {
        return activeTask() == null ? 0 : activeTask().id();
    }

    public JobQueue queue() {
        syncSettings();
        return queue;
    }
    /** Marks a queue plan as free time's own, so its jobs act under the autonomous rules. */
    public void markAutonomousPlan(long planId) {
        autonomousPlans.add(planId);
    }

    public void unmarkAutonomousPlan(long planId) {
        autonomousPlans.remove(planId);
    }
    /** True while the running task is a job of a plan free time started. */
    public boolean autonomousActive() {
        return activeTask() != null
                && activeTask().planId() > 0
                && autonomousPlans.contains(activeTask().planId());
    }
    /** True while a queued job belongs to the owner rather than to a plan free time started. */
    public boolean ownerWorkQueued() {
        for (Job job : queue.jobs())
            if (!JobQueue.terminal(job) && !autonomousPlans.contains(job.planId())) return true;
        return false;
    }
    /** The tool that started the running task, empty when nothing runs. */
    public String currentToolName() {
        return activeTask() == null ? "" : activeTask().toolName();
    }

    /** Active queue intent, including its durable plan goal when this task belongs to a plan. */
    public Optional<String> currentQueueIntent() {
        if (activeTask() == null || activeTask().jobId() <= 0) return Optional.empty();
        Job job = queue.job(activeTask().jobId()).orElse(null);
        if (job == null) return Optional.empty();
        String goal = job.planId() <= 0
                ? ""
                : queue.plan(job.planId()).map(p -> p.goal()).orElse("");
        return Optional.of(
                goal.isBlank()
                        ? "queue job #" + job.id() + " " + job.tool()
                        : "queue plan " + goal + " (" + job.tool() + ")");
    }
    /**
     * True while a task that is neither the default activity, free time's nor a movement order runs: owner work comes
     * first. A movement order ("wait here", "come here") says where to be, not what to do, so free time keeps its
     * shift instead of reading it as work in the way.
     */
    public boolean busyWithOther() {
        return activeTask() != null
                && !activeTask().defaultActivity()
                && !autonomousActive()
                && !movementOrder(activeTask());
    }
    /** True while the runner is only holding a movement order, which queued work may end. */
    public boolean movementOrderActive() {
        return movementOrder(activeTask());
    }

    public Optional<Job> activeJob() {
        return queue.active();
    }

    public int pendingJobs() {
        return (int)
                queue.jobs().stream().filter(j -> j.state() == JobState.PENDING).count();
    }

    public boolean hasWork() {
        return queue.jobCount() > 0 || (activeTask() != null && !activeTask().defaultActivity());
    }

    public java.util.List<dev.friendai.debug.QueueRow> queueRows() {
        return queue.jobCount() == 0 && queue.plans().stream().noneMatch(p -> p.cursor() != null)
                ? java.util.List.of()
                : dev.friendai.debug.QueueRows.build(
                        queue,
                        facts(),
                        activeTask() == null ? null : activeTask().task().describe());
    }

    public String queueSummary() {
        return queue.jobCount() == 0 && queue.plans().stream().noneMatch(p -> p.cursor() != null)
                ? current().map(Task::describe).orElse("none")
                : QueueText.summary(
                        queue,
                        facts(),
                        activeTask() == null ? null : activeTask().task().describe());
    }

    public long enqueue(QueuedStep step, StepFactory factory) {
        return inTransition(() -> enqueueActive(step, factory));
    }

    private long enqueueActive(QueuedStep step, StepFactory factory) {
        syncSettings();
        requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        Job job = queue.addDirect(step);
        factories.put(job.id(), factory);
        wakeQueue();
        if (activeTask() == null || activeTask().defaultActivity() || movementOrder(activeTask())) startNextQueued();
        return job.id();
    }

    public long enqueuePlan(String goal, List<QueuedStep> steps, List<StepFactory> stepFactories, PlanOptions options) {
        return enqueuePlan(goal, steps, stepFactories, options, id -> {});
    }
    /** {@code prepare} runs with the new plan id after it is queued but before any of its jobs can start. */
    public long enqueuePlan(
            String goal,
            List<QueuedStep> steps,
            List<StepFactory> stepFactories,
            PlanOptions options,
            java.util.function.LongConsumer prepare) {
        return inTransition(() -> enqueuePlanActive(goal, steps, stepFactories, options, prepare));
    }

    private long enqueuePlanActive(
            String goal,
            List<QueuedStep> steps,
            List<StepFactory> stepFactories,
            PlanOptions options,
            java.util.function.LongConsumer prepare) {
        syncSettings();
        if (steps.size() != stepFactories.size()) throw new IllegalArgumentException("steps and factories differ");
        for (QueuedStep step : steps) requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        JobQueue.Added added = queue.addPlan(goal, steps, options);
        for (int i = 0; i < stepFactories.size(); i++)
            factories.put(added.jobIds().get(i), stepFactories.get(i));
        prepare.accept(added.planId());
        wakeQueue();
        if (activeTask() == null || activeTask().defaultActivity() || movementOrder(activeTask())) startNextQueued();
        return added.planId();
    }

    public long enqueueStructuredPlan(
            String goal, PlanDefinition definition, PlanOptions options, Map<String, PlanStepFactory> stepFactories) {
        return inTransition(() -> enqueueStructuredPlanActive(goal, definition, options, stepFactories));
    }

    private long enqueueStructuredPlanActive(
            String goal, PlanDefinition definition, PlanOptions options, Map<String, PlanStepFactory> stepFactories) {
        syncSettings();
        for (PlanNode node : definition.nodes()) validateStructuredAuthorization(node);
        facts();
        long startTick = companion.level().getGameTime();
        long startDistance = sharedFacts == null ? 0 : sharedFacts.totalTravelledDistance();
        long planId = queue.addStructuredPlan(goal, definition, options);
        queue.updatePlanCursor(planId, PlanCursor.start(startTick, startDistance));
        structuredFactories.put(planId, Map.copyOf(stepFactories));
        wakeQueue();
        if (activeTask() == null || activeTask().defaultActivity() || movementOrder(activeTask())) startNextQueued();
        return planId;
    }

    public long enqueueStructuredPlan(
            PlanDefinition definition, PlanOptions options, Map<String, PlanStepFactory> stepFactories) {
        return enqueueStructuredPlan("", definition, options, stepFactories);
    }

    private void validateStructuredAuthorization(PlanNode node) {
        if (node instanceof PlanNode.Action action)
            requireCreativeAuthorization(
                    action.step().principal(), action.step().creativeSensitive());
        else if (node instanceof PlanNode.Conditional conditional) {
            conditional.thenNodes().forEach(this::validateStructuredAuthorization);
            conditional.elseNodes().forEach(this::validateStructuredAuthorization);
        } else ((PlanNode.Repeat) node).body().forEach(this::validateStructuredAuthorization);
    }

    public void wakeQueue() {
        blockedSince = -1;
        nextPoll = 0;
        factories.keySet().removeIf(id -> queue.job(id).isEmpty());
    }

    public void registerFactory(long jobId, StepFactory factory) {
        factories.put(jobId, factory);
    }

    /** Legacy task entry point. A new task freezes a queue job so the queue can resume afterwards. */
    public int start(Task task, String toolName) {
        return start(task, toolName, null, false);
    }

    public int start(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        return inTransition(() -> startActive(task, toolName, caller, creativeSensitive));
    }

    private int startActive(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        CreativeActionContext actionContext = actionContext(caller, creativeSensitive);
        requireCreativeAuthorization(actionContext == null ? null : actionContext.principal(), creativeSensitive);
        ActiveTask current = activeTask();
        if (current != null && current.jobId() > 0 && !current.interrupt()) activeTasks.freezeCurrent();
        else if (current != null && current.defaultActivity()) clearCurrentSilently();
        else if (current != null) cancelCurrent("replaced by a new task");
        return begin(task, toolName, task.emitsEvents(), false, caller, actionContext, creativeSensitive);
    }

    public void restore(Task task) {
        begin(task, task.type(), false, false);
    }

    public void interrupt(Task task, String toolName) {
        interrupt(task, toolName, null, false);
    }

    public void interrupt(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        inTransition(() -> interruptActive(task, toolName, caller, creativeSensitive));
    }

    private void interruptActive(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        CreativeActionContext actionContext = actionContext(caller, creativeSensitive);
        requireCreativeAuthorization(actionContext == null ? null : actionContext.principal(), creativeSensitive);
        ActiveTask current = activeTask();
        if (current != null && current.interrupt()) {
            cancelCurrent("replaced by urgent task");
        } else if (current != null && current.jobId() > 0) activeTasks.freezeCurrent();
        else if (current != null && current.defaultActivity()) clearCurrentSilently();
        else if (current != null) cancelCurrent("interrupted by urgent task");
        begin(task, toolName, task.emitsEvents(), true, caller, actionContext, creativeSensitive);
    }

    public void stop(String reason) {
        inTransition(() -> stopActive(reason));
    }

    private void stopActive(String reason) {
        if (activeTask() != null && activeTask().interrupt()) {
            cancelCurrent(reason);
            resumeFrozen();
            return;
        }
        ActiveTask active = activeTask();
        if (active != null && active.jobId() > 0) {
            Task cleanup = active.task().cleanupAfterCancel(context());
            cancelCurrent(reason);
            if (active.planId() > 0) {
                queue.cancelPlan(active.planId());
                finishPlan(active.planId(), "stopped by request", false);
            } else {
                queue.cancel(active.jobId());
                queue.remove(active.jobId());
                factories.remove(active.jobId());
            }
            if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
            else startNextOrDefault();
            return;
        }
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(context());
        if (active != null) cancelCurrent(reason);
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startNextOrDefault();
    }

    public void stopWithoutCleanup(String reason) {
        inTransition(() -> stopWithoutCleanupActive(reason));
    }

    private void stopWithoutCleanupActive(String reason) {
        if (activeTask() != null) cancelCurrent(reason);
        for (PlanRecord plan : queue.plans()) {
            queue.cancelPlan(plan.id());
            finishPlan(plan.id(), reason, false);
        }
        stopFrozen(reason);
        queue.clear();
        factories.clear();
        structuredFactories.clear();
        awaitingDecision.clear();
        startDefault();
    }
    /** Cancels stale work during a capability transition without reporting task failures to the brain. */
    public void cancelForCapabilityChange() {
        inTransition(this::cancelForCapabilityChangeActive);
    }

    private void cancelForCapabilityChangeActive() {
        ActiveTask active = activeTask();
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(context());
        ActiveTask frozen = frozenTask();
        if (frozen != null) {
            cleanup = combineCleanup(cleanup, frozen.task().cleanupAfterCancel(context(frozen)));
            stopFrozen("creative authority changed");
        }
        if (active != null) {
            activeTasks.stop(new StopRequest("creative authority changed"), context(active));
        }
        queue.clear();
        factories.clear();
        blockedPlans.clear();
        awaitingDecision.clear();
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startDefault();
    }

    public void stopAll(String reason) {
        inTransition(() -> stopAllActive(reason));
    }

    private void stopAllActive(String reason) {
        ActiveTask active = activeTask();
        Task cleanup = active == null ? null : active.task().cleanupAfterCancel(context());
        ActiveTask frozen = frozenTask();
        if (frozen != null) {
            cleanup = combineCleanup(cleanup, frozen.task().cleanupAfterCancel(context(frozen)));
            stopFrozen(reason);
        }
        if (active != null) cancelCurrent(reason);
        for (PlanRecord p : queue.plans()) finishPlan(p.id(), "stopped by request", false);
        queue.clear();
        factories.clear();
        awaitingDecision.clear();
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startDefault();
    }

    public String clearQueue(String reason) {
        return inTransition(() -> clearQueueActive(reason));
    }

    private String clearQueueActive(String reason) {
        ActiveTask active = activeTask();
        Task cleanup = active != null && active.jobId() > 0 ? active.task().cleanupAfterCancel(context()) : null;
        ActiveTask frozen = frozenTask();
        if (frozen != null) {
            cleanup = combineCleanup(cleanup, frozen.task().cleanupAfterCancel(context(frozen)));
            stopFrozen(reason);
        }
        if (active != null && active.jobId() > 0) cancelCurrent(reason);
        for (PlanRecord plan : queue.plans()) finishPlan(plan.id(), reason, false);
        String result = queue.clear().text();
        factories.clear();
        awaitingDecision.clear();
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else startNextOrDefault();
        return result;
    }

    public void cancelJob(long id, String reason) {
        inTransition(() -> cancelJobActive(id, reason));
    }

    private void cancelJobActive(long id, String reason) {
        Job original = queue.job(id).orElse(null);
        ActiveTask active = activeTask();
        Task cleanup = null;
        if (active != null && active.jobId() == id) {
            cleanup = active.task().cleanupAfterCancel(context());
            cancelCurrent(reason);
            queue.cancel(id);
            factories.remove(id);
        } else {
            ActiveTask frozen = frozenTask();
            if (frozen != null && frozen.jobId() == id) {
                cleanup = frozen.task().cleanupAfterCancel(context(frozen));
                stopFrozen(reason);
            }
            queue.cancel(id);
            factories.remove(id);
        }
        Job j = queue.job(id).orElse(null);
        if (j != null && j.planId() == 0) queue.remove(id);
        else if (j != null && isStructuredPlan(j.planId())) {
            queue.cancelPlan(j.planId());
            finishPlan(j.planId(), "action cancelled", false);
        } else if (j != null && j.planId() > 0 && queue.planStatus(j.planId()) != PlanStatus.ACTIVE)
            finishPlan(j.planId(), "cancelled", false);
        if (original != null && original.planId() == 0 && (active == null || active.jobId() != id))
            emit(sink -> sink.onCancelled((int) original.id(), original.tool(), reason));
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else if (activeTask() == null) startNextOrDefault();
    }

    public JobQueue.Result skipJob(long id) {
        return inTransition(() -> skipJobActive(id));
    }

    private JobQueue.Result skipJobActive(long id) {
        Job job = queue.job(id).orElse(null);
        if (job == null) return JobQueue.Result.error("unknown job #" + id);
        JobQueue.Result result = queue.skip(id);
        if (!result.ok()) return result;
        if (job.planId() == 0) {
            queue.remove(id);
            factories.remove(id);
        } else if (isStructuredPlan(job.planId()))
            advanceStructuredPlan(job.planId(), new PlanInterpreter.ActionResult.Succeeded());
        else if (queue.planStatus(job.planId()) == PlanStatus.DONE
                || queue.planStatus(job.planId()) == PlanStatus.FAILED)
            finishPlan(
                    job.planId(),
                    QueueText.planSummary(queue, job.planId()),
                    queue.planStatus(job.planId()) == PlanStatus.DONE);
        wakeQueue();
        if (activeTask() == null) startNextOrDefault();
        return result;
    }

    public void cancelPlan(long id, String reason) {
        inTransition(() -> cancelPlanActive(id, reason));
    }

    private void cancelPlanActive(long id, String reason) {
        ActiveTask active = activeTask();
        Task cleanup = null;
        if (active != null && active.planId() == id) {
            cleanup = active.task().cleanupAfterCancel(context());
            cancelCurrent(reason);
        }
        ActiveTask frozen = frozenTask();
        if (frozen != null && frozen.planId() == id) {
            cleanup = combineCleanup(cleanup, frozen.task().cleanupAfterCancel(context(frozen)));
            stopFrozen(reason);
        }
        queue.cancelPlan(id);
        finishPlan(id, "cancelled", false);
        if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
        else if (activeTask() == null) startNextOrDefault();
    }

    public Optional<String> stopNamed(String name, String reason) {
        ActiveTask a = activeTask();
        if (a == null || !(a.toolName().equals(name) || a.task().matches(name))) return Optional.empty();
        String description = a.task().describe();
        stop(reason);
        return Optional.of(description);
    }

    public void tick() {
        inTransition(this::tickActive);
    }

    private void tickActive() {
        syncSettings();
        revalidateCreativeHover();
        if (paused || suspended || held()) return;
        if (activeTask() != null && activeTask().defaultActivity() && !mayFollowOwner.getAsBoolean())
            clearCurrentSilently();
        queue.advanceClock();
        expireDeadlines();
        boolean hasStructuredPlan = false;
        for (PlanRecord plan : queue.plans())
            if (plan.cursor() != null && !plan.cursor().stack().isEmpty()) {
                hasStructuredPlan = true;
                break;
            }
        if (hasStructuredPlan) {
            if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
            sharedFacts.refresh();
        }
        if (activeTask() == null) {
            if (!pollQueue()) startDefault();
            return;
        }
        if ((activeTask().defaultActivity() || movementOrder(activeTask())) && pollQueue()) return;
        ActiveTask active = activeTask();
        if (active.planId() > 0)
            queue.plan(active.planId()).filter(p -> p.cursor() != null).ifPresent(this::planFacts);
        if (active.creativeSensitive()
                && companion.creativeMode()
                && !CreativeModeService.isActionAuthorized(
                        companion, active.creativeActionContext(), CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return;
        }
        Optional<ActiveTaskController.Completion> completion = activeTasks.tick(context(active));
        if (completion.isPresent()) {
            ActiveTaskController.Completion result = completion.get();
            completeActive(result.active(), result.status(), result.summary());
        } else if (activeTask() == active) maybeNoticeBlocked();
    }

    private void revalidateCreativeHover() {
        if (!companion.creativeMode() || companion.creativeLanding() || !companion.isNoGravity()) return;
        if (currentCreativeActionAllowed(CreativeAction.FLIGHT)
                || CreativeModeService.ownerMayUsePassiveFlight(companion)) return;
        companion.endCreativeFlight(false);
        companion.getNavigation().stop();
    }

    private void completeActive(ActiveTask active, TaskStatus status, String summary) {
        String result = summary == null ? active.task().resultSummary() : summary;
        // One place per finished task: a thing the companion built, farmed or mined is worth remembering itself.
        if (status == TaskStatus.SUCCESS && eventSink != null) {
            PlaceCandidate candidate = active.task().placeCandidate();
            if (candidate != null) emit(sink -> sink.onPlace(candidate, active.toolName()));
        }
        if (active.interrupt()) {
            if (active.emit()) emit(sink -> sink.onFinished(active.id(), active.toolName(), status, result));
            resumeFrozen();
            return;
        }
        if (active.jobId() > 0) {
            boolean retried = false;
            if (status == TaskStatus.SUCCESS) queue.finish(active.jobId(), result);
            else retried = queue.fail(active.jobId(), result).retried();
            if (!retried)
                emit(sink -> sink.onJobFinished(active.jobId(), active.planId(), active.toolName(), status, result));
            if (active.planId() == 0) {
                if (!retried) {
                    if (active.task().emitsEvents())
                        emit(sink -> sink.onFinished(active.id(), active.toolName(), status, result));
                    queue.remove(active.jobId());
                    factories.remove(active.jobId());
                }
            } else if (isStructuredPlan(active.planId())) {
                if (!retried) {
                    if (status == TaskStatus.SUCCESS)
                        advanceStructuredPlan(active.planId(), new PlanInterpreter.ActionResult.Succeeded());
                    else handleStructuredActionFailure(active.planId(), active.jobId(), result);
                }
            } else if (queue.planStatus(active.planId()) == PlanStatus.NEEDS_DECISION) {
                awaitingDecision.add(active.planId());
                notifyBlocked(active.planId(), active.jobId(), result);
            } else if (queue.planStatus(active.planId()) == PlanStatus.DONE
                    || queue.planStatus(active.planId()) == PlanStatus.FAILED)
                finishPlan(
                        active.planId(),
                        QueueText.planSummary(queue, active.planId()),
                        queue.planStatus(active.planId()) == PlanStatus.DONE);
        } else if (active.emit()) emit(sink -> sink.onFinished(active.id(), active.toolName(), status, result));
        if (frozenTask() != null) {
            resumeFrozen();
            return;
        }
        if (!startNextQueued()) startDefault();
    }

    private int begin(Task task, String name, boolean emit, boolean interrupt) {
        return begin(task, name, emit, interrupt, null, null, false);
    }

    private int begin(
            Task task,
            String name,
            boolean emit,
            boolean interrupt,
            Caller caller,
            CreativeActionContext actionContext,
            boolean creativeSensitive) {
        ServerSettings settings = settingsSupplier.get();
        TaskStartContext start = new TaskStartContext(
                name,
                caller,
                actionContext,
                emit,
                false,
                interrupt,
                activeTasks.allocateId(emit),
                0,
                0,
                creativeSensitive,
                settings,
                baseContext());
        try {
            return activeTasks.start(task, start);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to start", name, e);
            if (emit)
                emit(sink -> sink.onFinished(
                        start.id(),
                        name,
                        TaskStatus.FAILED,
                        "task crashed: " + e.getClass().getSimpleName()));
            if (interrupt) resumeFrozen();
            else startNextOrDefault();
            return start.id();
        }
    }

    private void cancelCurrent(String reason) {
        ActiveTask a = activeTask();
        if (a == null) return;
        activeTasks.stop(new StopRequest(reason), baseContext());
        if (a.jobId() > 0) {
            queue.cancel(a.jobId());
            emit(sink -> sink.onJobFinished(a.jobId(), a.planId(), a.toolName(), TaskStatus.FAILED, reason));
            if (a.planId() == 0 && a.emit()) emit(sink -> sink.onCancelled(a.id(), a.toolName(), reason));
        } else if (a.emit()) emit(sink -> sink.onCancelled(a.id(), a.toolName(), reason));
    }

    private void clearCurrentSilently() {
        activeTasks.stop(new StopRequest("cleared without cancellation event"), baseContext());
    }
    /** Idle-time start attempt, throttled so a blocked queue is not re-scanned every tick. */
    private boolean pollQueue() {
        if ((queue.jobCount() == 0 && queue.plans().stream().noneMatch(plan -> plan.cursor() != null))
                || queue.clock() < nextPoll) return false;
        boolean started = startNextQueued();
        if (!started) nextPoll = queue.clock() + POLL_INTERVAL_TICKS;
        return started;
    }

    private GuardFacts facts() {
        if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
        sharedFacts.refresh();
        return sharedFacts;
    }

    private GuardFacts planFacts(PlanRecord plan) {
        if (sharedFacts == null) sharedFacts = new CompanionGuardFacts(companion);
        long baseline = plan.cursor() == null ? 0 : plan.cursor().startDistance();
        sharedFacts.refresh(
                plan.cursor() == null ? plan.addedTick() : plan.cursor().startTick(), baseline);
        return sharedFacts;
    }

    private boolean isStructuredPlan(long planId) {
        return queue.plan(planId)
                .map(plan -> plan.definition() != null && plan.cursor() != null)
                .orElse(false);
    }

    private boolean advanceNextStructuredPlan() {
        PlanRecord selected = null;
        for (PlanRecord plan : queue.plans()) {
            if (plan.cursor() == null
                    || !plan.cursor().pendingActionPath().isEmpty()
                    || plan.cursor().stack().isEmpty()) continue;
            if (selected == null
                    || plan.priority() > selected.priority()
                    || plan.priority() == selected.priority() && plan.addedTick() < selected.addedTick())
                selected = plan;
        }
        if (selected == null) return false;
        advanceStructuredPlan(selected.id(), new PlanInterpreter.ActionResult.None());
        return true;
    }

    private void advanceStructuredPlan(long planId, PlanInterpreter.ActionResult result) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null || plan.cursor() == null) return;
        var transition = PlanInterpreter.advance(plan.definition(), plan.cursor(), planFacts(plan), result);
        queue.updatePlanCursor(planId, transition.cursor());
        if (transition.event() instanceof PlanInterpreter.Event.EmitAction emit) {
            QueuedStep action = emit.step();
            PlanStepFactory factory =
                    structuredFactories.getOrDefault(planId, Map.of()).get(action.tool());
            if (factory == null) {
                queue.cancelPlan(planId);
                finishPlan(planId, "task factory is unavailable for " + action.tool() + " at " + emit.path(), false);
                return;
            }
            TaskContext taskContext = new TaskContext(
                    companion,
                    (ServerLevel) companion.level(),
                    this,
                    actionContext(action.principal(), action.creativeSensitive()));
            if (!factory.authorized(taskContext, action)) {
                queue.cancelPlan(planId);
                finishPlan(planId, "access to " + action.tool() + " was revoked at " + emit.path(), false);
                return;
            }
            try {
                requireCreativeAuthorization(action.principal(), action.creativeSensitive());
                QueuedStep emitted = new QueuedStep(
                        action.tool(),
                        action.args(),
                        action.priority(),
                        action.guards(),
                        action.onUnmet(),
                        action.onFailure(),
                        action.maxAttempts(),
                        List.of(),
                        action.principal(),
                        action.creativeSensitive());
                Job job = queue.addJob(planId, emitted);
                queue.updatePlanCursor(
                        planId, queue.plan(planId).orElseThrow().cursor().withPendingJob(job.id()));
                factories.put(job.id(), context -> factory.create(context, emitted));
            } catch (RuntimeException exception) {
                queue.cancelPlan(planId);
                finishPlan(planId, "could not emit action at " + emit.path() + ": " + exception.getMessage(), false);
            }
        } else if (transition.event() instanceof PlanInterpreter.Event.Complete) {
            finishPlan(planId, "conditional plan completed", true);
        } else if (transition.event() instanceof PlanInterpreter.Event.Failed failed) {
            queue.cancelPlan(planId);
            finishPlan(planId, failed.path() + ": " + failed.reason(), false);
        }
    }

    private boolean startNextQueued() {
        if ((queue.jobCount() == 0 && queue.plans().stream().noneMatch(plan -> plan.cursor() != null))
                || paused
                || suspended
                || held()) return false;
        GuardFacts facts = facts();
        skipUnmet(facts);
        expireDeadlines();
        Optional<Job> candidate = QueuePolicy.next(queue, facts);
        if (candidate.isEmpty() && advanceNextStructuredPlan()) {
            facts = facts();
            candidate = QueuePolicy.next(queue, facts);
        }
        if (candidate.isEmpty()) {
            maybeNoticeBlocked();
            return false;
        }
        if (activeTask() != null && activeTask().defaultActivity()) clearCurrentSilently();
        else if (activeTask() != null && movementOrder(activeTask())) clearCurrentSilently();
        else if (activeTask() != null) return false;
        Job job = candidate.get();
        StepFactory factory = factories.get(job.id());
        if (factory == null) {
            if (isStructuredPlan(job.planId())) {
                queue.cancelPlan(job.planId());
                finishPlan(job.planId(), "task factory is unavailable for " + job.tool(), false);
            } else {
                queue.fail(job.id(), "task factory is unavailable");
                if (job.planId() > 0) notifyBlocked(job.planId(), job.id(), "task factory is unavailable");
            }
            return startNextQueued();
        }
        CreativeActionContext actionContext = actionContext(job.principal(), job.creativeSensitive());
        if (companion.creativeMode()
                && job.creativeSensitive()
                && !CreativeModeService.isActionAuthorized(
                        companion, actionContext, CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return false;
        }
        if (isStructuredPlan(job.planId())) {
            PlanStepFactory planFactory =
                    structuredFactories.getOrDefault(job.planId(), Map.of()).get(job.tool());
            QueuedStep step = step(job);
            TaskContext authorizationContext = new TaskContext(
                    companion, (ServerLevel) companion.level(), this, actionContext, settingsSupplier.get());
            if (planFactory == null || !planFactory.authorized(authorizationContext, step)) {
                queue.cancelPlan(job.planId());
                finishPlan(job.planId(), "access to " + job.tool() + " was revoked before the action started", false);
                return startNextQueued();
            }
        }
        try {
            ServerSettings settings = settingsSupplier.get();
            Task task = factory.create(
                    new TaskContext(companion, (ServerLevel) companion.level(), this, actionContext, settings));
            queue.markRunning(job.id());
            awaitingDecision.remove(job.planId());
            activeTasks.start(
                    task,
                    new TaskStartContext(
                            job.tool(),
                            null,
                            actionContext,
                            true,
                            false,
                            false,
                            (int) job.id(),
                            job.id(),
                            job.planId(),
                            job.creativeSensitive(),
                            settings,
                            new TaskContext(
                                    companion, (ServerLevel) companion.level(), this, actionContext, settings)));
            blockedSince = -1;
            return true;
        } catch (RuntimeException e) {
            String reason = e instanceof IllegalArgumentException
                    ? e.getMessage()
                    : "task crashed: " + e.getClass().getSimpleName();
            var failed = queue.fail(job.id(), reason);
            if (!failed.retried() && isStructuredPlan(job.planId()))
                handleStructuredActionFailure(job.planId(), job.id(), reason);
            else if (!failed.retried() && job.planId() > 0) {
                if (queue.planStatus(job.planId()) == PlanStatus.NEEDS_DECISION) {
                    awaitingDecision.add(job.planId());
                    notifyBlocked(job.planId(), job.id(), reason);
                } else if (queue.planStatus(job.planId()) == PlanStatus.FAILED) finishPlan(job.planId(), reason, false);
            }
            return startNextQueued();
        }
    }

    private void skipUnmet(GuardFacts facts) {
        for (Job j : queue.jobs())
            if (j.state() == JobState.PENDING
                    && j.onUnmet() == GuardAction.SKIP
                    && j.guards().stream().anyMatch(g -> !g.holds(facts))) {
                queue.skip(j.id());
                if (isStructuredPlan(j.planId()))
                    advanceStructuredPlan(j.planId(), new PlanInterpreter.ActionResult.Succeeded());
                else if (j.planId() > 0 && queue.planStatus(j.planId()) != PlanStatus.ACTIVE)
                    finishPlan(
                            j.planId(),
                            QueueText.planSummary(queue, j.planId()),
                            queue.planStatus(j.planId()) == PlanStatus.DONE);
            }
    }

    private void handleStructuredActionFailure(long planId, long jobId, String reason) {
        PlanRecord plan = queue.plan(planId).orElse(null);
        if (plan == null) return;
        FailurePolicy policy = queue.job(jobId).map(Job::onFailure).orElse(plan.onFailure());
        switch (policy) {
            case SKIP -> advanceStructuredPlan(planId, new PlanInterpreter.ActionResult.Succeeded());
            case STOP_PLAN -> {
                queue.cancelPlan(planId);
                finishPlan(planId, reason, false);
            }
            case ASK -> {
                awaitingDecision.add(planId);
                notifyBlocked(planId, jobId, reason);
            }
        }
    }

    private static QueuedStep step(Job job) {
        return new QueuedStep(
                job.tool(),
                job.args(),
                job.priorityExplicit() ? job.priority() : null,
                job.guards(),
                job.onUnmet(),
                job.onFailure(),
                job.maxAttempts(),
                List.of(),
                job.principal(),
                job.creativeSensitive());
    }

    private void maybeNoticeBlocked() {
        if (activeTask() != null && activeTask().jobId() > 0) return;
        if (blockedSince < 0) blockedSince = queue.clock();
        if (queue.clock() - blockedSince < settingsSupplier.get().tasks().queueBlockedNoticeSec() * 20) return;
        GuardFacts facts = facts();
        for (PlanRecord plan : queue.plans()) {
            if (queue.planStatus(plan.id()) != PlanStatus.ACTIVE) continue;
            // The first pending step that is gated by its guards, not by a predecessor or a retry delay.
            Job gating = queue.jobsOfPlan(plan.id()).stream()
                    .filter(j -> j.state() == JobState.PENDING
                            && j.retryAtTick() <= queue.clock()
                            && QueuePolicy.dependenciesDone(queue, j))
                    .findFirst()
                    .orElse(null);
            if (gating == null) continue;
            if (QueuePolicy.ready(queue, gating, facts)) {
                blockedPlans.remove(plan.id());
                continue;
            }
            String reason =
                    QueueText.truncate(QueuePolicy.notReadyReason(queue, gating, facts), QueueText.MAX_BLOCKED_REASON);
            if (!reason.equals(blockedPlans.get(plan.id()))) notifyBlocked(plan.id(), gating.id(), reason);
        }
    }

    private void notifyBlocked(long plan, long jobId, String reason) {
        Job job = queue.job(jobId).orElse(null);
        if (job == null) return;
        String goal = queue.plan(plan).map(PlanRecord::goal).orElse("");
        int remaining = (int) queue.jobsOfPlan(plan).stream()
                .filter(j -> j.state() == JobState.PENDING)
                .count();
        String bounded = QueueText.truncate(reason, QueueText.MAX_BLOCKED_REASON);
        blockedPlans.put(plan, bounded);
        emit(sink -> sink.onPlanBlocked(plan, goal, jobId, job.tool(), bounded, remaining));
    }

    private void finishPlan(long id, String summary, boolean success) {
        PlanRecord p = queue.plan(id).orElse(null);
        if (p == null) return;
        String goal = p.goal();
        queue.archivePlan(id, success, summary);
        factories.entrySet().removeIf(e -> queue.job(e.getKey()).isEmpty());
        structuredFactories.remove(id);
        blockedPlans.remove(id);
        awaitingDecision.remove(id);
        emit(sink -> sink.onPlanFinished(id, goal, success, summary));
    }

    private void expireDeadlines() {
        for (long id : queue.expiredPlans()) {
            ActiveTask a = activeTask();
            if (a != null && a.planId() == id) {
                Task cleanup = a.task().cleanupAfterCancel(context());
                cancelCurrent("ran out of time");
                if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
            }
            queue.cancelPlan(id);
            finishPlan(id, "ran out of time", false);
        }
    }

    private void resumeFrozen() {
        ActiveTask old = frozenTask();
        if (old != null
                && queue.job(old.jobId())
                        .filter(j -> j.state() == JobState.RUNNING)
                        .isPresent()) {
            activeTasks.takeFrozen();
            activeTasks.resumeFrozen(old);
        } else {
            if (old != null) stopFrozen("frozen task is no longer queued");
            startNextOrDefault();
        }
    }

    private void startNextOrDefault() {
        if (activeTask() != null) return;
        if (frozenTask() != null) {
            resumeFrozen();
            return;
        }
        if (!startNextQueued()) startDefault();
    }

    private Task combineCleanup(Task first, Task second) {
        if (first == null) return second;
        if (second == null) return first;
        return new SequenceTask(
                "scaffold cleanup",
                List.of(
                        new PlanEntry("scaffold_cleanup", ctx -> first),
                        new PlanEntry("scaffold_cleanup", ctx -> second)),
                () -> timeoutSec.getAsInt() * 20);
    }

    private void startDefault() {
        if (!paused
                && !held()
                && !suspended
                && activeTask() == null
                && !plansAwaitingDecision()
                && defaultFollow.getAsBoolean()
                && mayFollowOwner.getAsBoolean()) {
            ServerSettings settings = settingsSupplier.get();
            try {
                activeTasks.start(
                        new FollowTask(true),
                        new TaskStartContext(
                                "follow", null, null, false, true, false, 0, 0, 0, false, settings, baseContext()));
            } catch (RuntimeException e) {
                LOGGER.warn("Default follow failed to start", e);
            }
        }
    }
    /**
     * A plan whose remaining steps wait for a decision is not idleness: the companion stands where it is and the
     * failure is reported from there, so the follow cannot preempt the work it is about.
     */
    private boolean plansAwaitingDecision() {
        if (awaitingDecision.isEmpty()) return false;
        awaitingDecision.removeIf(id -> queue.planStatus(id) != PlanStatus.NEEDS_DECISION);
        return !awaitingDecision.isEmpty();
    }

    public long persistenceActiveJobId() {
        return activeTask() != null && activeTask().jobId() > 0
                ? activeTask().jobId()
                : frozenTask() == null ? 0 : frozenTask().jobId();
    }

    public Optional<TaskState> persistenceTaskState() {
        ActiveTask active = activeTask() != null && activeTask().jobId() > 0
                ? activeTask()
                : frozenTask() != null ? frozenTask() : activeTask();
        if (active == null || active.defaultActivity() || active.task().type().equals("follow"))
            return Optional.empty();
        String data;
        try {
            data = active.task().saveData();
        } catch (RuntimeException error) {
            LOGGER.warn("Task {} failed to save its checkpoint", active.toolName(), error);
            return Optional.empty();
        }
        if (data == null) return Optional.empty();
        try {
            return Optional.of(new TaskState(active.task().type(), data));
        } catch (IllegalArgumentException oversized) {
            LOGGER.warn("Task {} checkpoint exceeds the save limit", active.toolName());
            return Optional.empty();
        }
    }

    /** Installs validated state; decoding, authorization and recovery policy belong to TaskPersistenceCoordinator. */
    public void installRestoredState(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask) {
        inTransition(() -> installRestoredStateActive(decoded, restoredFactories, restoredPlanFactories, restoredTask));
    }

    private void installRestoredStateActive(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask) {
        syncSettings();
        queue = decoded.queue();
        queue.setLimits(queueSettings.queueLimits());
        factories.clear();
        factories.putAll(restoredFactories);
        structuredFactories.clear();
        restoredPlanFactories.forEach((id, map) -> structuredFactories.put(id, Map.copyOf(map)));
        activeTasks.reset();
        Job activeJob = decoded.activeJobId() == 0
                ? null
                : queue.job(decoded.activeJobId()).orElse(null);
        if (restoredTask != null && activeJob != null && activeJob.state() == JobState.RUNNING) {
            CreativeActionContext actionContext = actionContext(activeJob.principal(), activeJob.creativeSensitive());
            try {
                ServerSettings settings = settingsSupplier.get();
                activeTasks.start(
                        restoredTask,
                        new TaskStartContext(
                                activeJob.tool(),
                                null,
                                actionContext,
                                true,
                                false,
                                false,
                                (int) activeJob.id(),
                                activeJob.id(),
                                activeJob.planId(),
                                activeJob.creativeSensitive(),
                                settings,
                                new TaskContext(
                                        companion, (ServerLevel) companion.level(), this, actionContext, settings)));
            } catch (RuntimeException e) {
                queue.fail(activeJob.id(), "task crashed: " + e.getClass().getSimpleName());
            }
        } else if (restoredTask != null && activeJob == null) {
            restore(restoredTask);
        }
        for (Job job : List.copyOf(queue.jobs()))
            if (job.state() == JobState.RUNNING
                    && (activeTask() == null || activeTask().jobId() != job.id())) queue.requeue(job.id());
        awaitingDecision.clear();
        for (PlanRecord plan : queue.plans())
            if (queue.planStatus(plan.id()) == PlanStatus.NEEDS_DECISION) awaitingDecision.add(plan.id());
        nextPoll = 0;
    }

    private TaskContext context() {
        ActiveTask active = activeTask() != null ? activeTask() : frozenTask();
        ServerSettings settings = active == null ? settingsSupplier.get() : active.settings();
        CreativeActionContext actionContext = active == null ? null : active.creativeActionContext();
        return context(settings, actionContext);
    }

    private TaskContext context(ActiveTask active) {
        return context(active.settings(), active.creativeActionContext());
    }

    private TaskContext baseContext() {
        return context(settingsSupplier.get(), null);
    }

    private ActiveTask activeTask() {
        return activeTasks.currentActive();
    }

    private ActiveTask frozenTask() {
        return activeTasks.frozenActive();
    }

    private void stopFrozen(String reason) {
        ActiveTask frozen = frozenTask();
        if (frozen != null) activeTasks.stopFrozen(new StopRequest(reason), context(frozen));
    }

    private void emit(java.util.function.Consumer<TaskEventSink> event) {
        if (eventSink == null) return;
        if (transitionDepth > 0 || flushingEvents) {
            pendingEvents.addLast(event);
            return;
        }
        event.accept(eventSink);
    }

    private <T> T inTransition(java.util.function.Supplier<T> action) {
        transitionDepth++;
        try {
            return action.get();
        } finally {
            transitionDepth--;
            if (transitionDepth == 0) flushEvents();
        }
    }

    private void inTransition(Runnable action) {
        inTransition(() -> {
            action.run();
            return null;
        });
    }

    private void flushEvents() {
        if (flushingEvents || eventSink == null) return;
        flushingEvents = true;
        try {
            while (!pendingEvents.isEmpty()) pendingEvents.removeFirst().accept(eventSink);
        } finally {
            flushingEvents = false;
        }
    }

    private TaskContext context(ServerSettings settings, CreativeActionContext actionContext) {
        return new TaskContext(companion, (ServerLevel) companion.level(), this, actionContext, settings);
    }

    private CreativeActionContext actionContext(Caller caller, boolean creativeSensitive) {
        return caller == null || caller.uuid() == null || !creativeSensitive
                ? null
                : new CreativeActionContext(caller.uuid(), companion.creativeModeRevision());
    }

    private CreativeActionContext actionContext(java.util.UUID principal, boolean creativeSensitive) {
        return principal == null || !creativeSensitive
                ? null
                : new CreativeActionContext(principal, companion.creativeModeRevision());
    }

    private void requireCreativeAuthorization(java.util.UUID principal, boolean creativeSensitive) {
        if (!companion.creativeMode() || !creativeSensitive) return;
        CreativeActionContext context = actionContext(principal, true);
        if (!CreativeModeService.isActionAuthorized(companion, context, CreativeAction.WORLD_CHANGING_TASK)) {
            throw new IllegalArgumentException(
                    "creative mode requires its online operator owner for world-changing tasks");
        }
    }
}
