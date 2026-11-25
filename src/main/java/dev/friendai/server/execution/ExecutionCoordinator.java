package dev.friendai.server.execution;

import com.google.gson.JsonObject;
import dev.friendai.agent.verify.LoopMetrics;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.log.ExecutionTiming;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.execution.log.TaskLogEvents;
import dev.friendai.execution.queue.GuardFacts;
import dev.friendai.execution.queue.HoldKey;
import dev.friendai.execution.queue.IdleRepairPolicy;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanInterpreter;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.PlanRecord;
import dev.friendai.execution.queue.PlanStatus;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueuePolicy;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.queue.RetryPolicy;
import dev.friendai.execution.queue.RetryResume;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.creative.CreativeAction;
import dev.friendai.features.creative.CreativeActionContext;
import dev.friendai.features.navigation.minecraft.task.FollowTask;
import dev.friendai.features.places.PlaceCandidate;
import dev.friendai.foundation.diagnostics.DiagnosticClock;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.movement.MovementOrders;
import dev.friendai.server.configuration.ServerSettings;
import dev.friendai.server.creative.CreativeModeService;
import dev.friendai.server.execution.ActiveTaskController.ActiveTask;
import dev.friendai.server.execution.tools.CheckpointRestorer;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskExecutionPort;
import dev.friendai.task.TaskState;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.plan.SequenceTask;
import dev.friendai.task.plan.StepFactory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs one task at a time and schedules durable jobs when no urgent or direct task owns the coordinator. */
public final class ExecutionCoordinator implements TaskExecutionPort {
    private static final int POLL_INTERVAL_TICKS = 10;
    /** The one reason running, frozen and restored work gives when the grant behind it was withdrawn. */
    static final String REVOKED = "the grant behind this work was revoked";

    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/task");
    private final CompanionEntity companion;
    private final IntSupplier timeoutSec;
    private final BooleanSupplier defaultFollow;
    private final Supplier<ServerSettings> settingsSupplier;
    private final ActiveTaskController activeTasks;
    private final QueueDriver queueDriver;
    /** Cancel, skip and clear: every operation that mutates the queue from outside the tick. */
    private final QueueMutationService mutations;
    /** What this companion's loop decided in the current window; written to the debug log and then reset. */
    private final RunnerMetricsView metrics = new RunnerMetricsView();
    /** Whether the idle fallback may walk to the owner; free time narrows it (see DefaultActivityPolicy). */
    private final BooleanSupplier mayFollowOwner;
    /** A movement order is a task without a job behind it, so waiting in place never pins the queue. */
    private static boolean movementOrder(ActiveTask active) {
        return active != null && active.jobId() == 0 && MovementOrders.isOrder(active.toolName());
    }

    private final TaskEventDispatcher events = new TaskEventDispatcher();

    private RepairSteps repairFactory;
    /** Rebuilds a task from the checkpoint its failed attempt saved, so a retry continues instead of repeating. */
    private CheckpointRestorer retryRestore;
    /** Whether a tool commits world effects before it finishes; the retry policy asks this about a crashed attempt. */
    private Predicate<String> commitsWorldEffects = tool -> false;
    /** Progress left by failed attempts, keyed by job and checked against its arguments at the next activation. */
    private final RetryCheckpoints retryCheckpoints = new RetryCheckpoints();

    private dev.friendai.task.structure.StructureBuilder structureBuilder =
            new dev.friendai.features.construction.minecraft.build.ConstructionStructureBuilder();
    private final java.util.Set<String> repairRequests = new java.util.HashSet<>();
    /** Each live automatic remedy and the blocked step it exists for; bounded so a long run cannot grow it. */
    private final java.util.LinkedHashMap<Long, RepairTarget> repairs = new java.util.LinkedHashMap<>();

    /** Enough for every job a default queue can hold; beyond that the oldest entry is dropped, not the work. */
    private static final int MAX_TRACKED_REPAIRS = 64;

    private static final dev.friendai.execution.queue.ToolRepairRule REPAIR_TOOLS =
            dev.friendai.execution.queue.ToolRepairRule.vanillaPickaxes();
    private int idleRepairAttempts;
    private int idleRepairInserted;

    /** The last direct job the companion started, kept so the owner can retry it once it stopped or failed. */
    private Started lastStarted;

    /** How that job ended, so a Retry click names a job whose outcome is known instead of replaying whatever is last. */
    private RetryPolicy.Last lastDirect;

    /** The tool invocation the runner is executing right now; runner-local, never read by a worker. */
    private LogContext invocationContext = LogContext.NONE;

    /** How often the material reserve is recomposed while nothing transitions; one second of game time. */
    private static final long MATERIAL_RESERVE_INTERVAL = 20L;

    private long materialReserveTick = Long.MIN_VALUE;
    private int materialReserveTaskId = -1;

    /** A gap shorter than this is an ordinary slow frame, not a stall worth a record. */
    private static final long GAP_THRESHOLD_MS = 1_000L;

    /** A tick this short cannot explain a gap even when the wall clock moved by less than that. */
    private static final long MIN_ATTRIBUTABLE_WORK_MS = 250L;

    /** How much of the gap the measured work may leave unexplained before the reason stays unknown. */
    private static final long GAP_ATTRIBUTION_SLACK_MS = 100L;

    private final DiagnosticClock diagnosticClock;
    private long lastTickWall = -1L;
    private boolean clockGapReported;
    /** How long the last completed runner tick itself took, so a gap can be attributed to work this tick really did. */
    private long lastTickWorkNanos;

    private record Started(long jobId, QueuedStep step, StepFactory factory, LogContext context) {}

    private boolean paused;
    private boolean suspended;
    /** The owner asked for a hold; a later join must not undo it. */
    private boolean manualPause;

    private boolean stoppedByOrder;
    /** A restored task held back because its owner was away; resume() starts it before the queue. */
    private Task pendingRestoredTask;

    private long pendingRestoredJobId;
    /** The active budget the pending restored task already spent before the save, and the timeout it ran under. */
    private int pendingRestoredElapsedTicks;

    private int pendingRestoredTimeoutSec;

    private final HoldRegistry holds = new HoldRegistry();

    public ExecutionCoordinator(CompanionEntity companion, IntSupplier timeoutSec, BooleanSupplier defaultFollow) {
        this(companion, timeoutSec, defaultFollow, () -> true, companion::serverSettings);
    }
    /** A runner that knows whether following is its business at all; the real entity passes the free-time gate. */
    public ExecutionCoordinator(
            CompanionEntity companion,
            IntSupplier timeoutSec,
            BooleanSupplier defaultFollow,
            BooleanSupplier mayFollowOwner) {
        this(companion, timeoutSec, defaultFollow, mayFollowOwner, companion::serverSettings);
    }

    public ExecutionCoordinator(
            CompanionEntity companion,
            IntSupplier timeoutSec,
            BooleanSupplier defaultFollow,
            BooleanSupplier mayFollowOwner,
            Supplier<ServerSettings> settingsSupplier) {
        this(companion, timeoutSec, defaultFollow, mayFollowOwner, settingsSupplier, DiagnosticClock.system());
    }

    ExecutionCoordinator(
            CompanionEntity companion,
            IntSupplier timeoutSec,
            BooleanSupplier defaultFollow,
            BooleanSupplier mayFollowOwner,
            Supplier<ServerSettings> settingsSupplier,
            DiagnosticClock clock) {
        this.companion = companion;
        this.timeoutSec = timeoutSec;
        this.defaultFollow = defaultFollow;
        this.mayFollowOwner = mayFollowOwner;
        this.settingsSupplier = settingsSupplier;
        this.diagnosticClock = clock;
        this.activeTasks = new ActiveTaskController(timeoutSec, clock);
        this.activeTasks.setDiagnostics(event -> emit(sink -> sink.onDiagnostic(event)));
        this.queueDriver = new QueueDriver(this);
        this.mutations = new QueueMutationService(this);
    }

    public void applySettings(ServerSettings settings) {
        queueDriver.applySettings(settings);
    }

    private void syncSettings() {
        applySettings(settingsSupplier.get());
    }

    /** The queue owner, shared with the queue driver and the mutation service. */
    QueueDriver queueDriver() {
        return queueDriver;
    }

    RunnerMetricsView metrics() {
        return metrics;
    }

    ActiveTaskController activeTasks() {
        return activeTasks;
    }

    CompanionEntity companion() {
        return companion;
    }

    /** The task factory of the tool that can supply a missing fact, and the creative authority it declares. */
    public record RepairPlan(StepFactory factory, boolean creativeSensitive) {
        public RepairPlan {
            if (factory == null) throw new IllegalArgumentException("a repair needs a step factory");
        }
    }

    /**
     * How a pending job's unmet, repairable fact becomes a task: the admission gate and the task factory of the tool
     * that can supply it, scoped to the blocked job's requester. A runner without one starts no automatic repair.
     */
    @FunctionalInterface
    public interface RepairSteps {
        /** The task to insert, or empty when the requester may not use the repair tool at all. */
        Optional<RepairPlan> forCaller(java.util.UUID principal, JsonObject args);
    }

    public void setRepairFactory(RepairSteps factory) {
        this.repairFactory = factory;
    }

    /**
     * Supplies the decoders that rebuild a task from the checkpoint a failed attempt saved. A runner without one can
     * only restart a job, which is correct for every tool that commits nothing before it finishes.
     */
    public void setRetryRestore(CheckpointRestorer restorer) {
        this.retryRestore = restorer;
    }

    /** Tells the retry policy which tools may have changed the world before a crash stopped them. */
    public void setCommitsWorldEffects(Predicate<String> policy) {
        this.commitsWorldEffects = policy == null ? tool -> false : policy;
    }

    /** The progress kept for jobs that failed and may run again; the persistence layer saves and restores it. */
    public RetryCheckpoints retryCheckpoints() {
        return retryCheckpoints;
    }

    /** Keeps a failed attempt's checkpoint for a job, so its next attempt resumes rather than repeats. */
    private void rememberRetry(ActiveTask active, boolean crashed) {
        String tool = active.toolName();
        Optional<TaskState> state = crashed ? Optional.empty() : persistenceState(active.task(), "task " + tool);
        JsonObject args = queueDriver.queue().job(active.jobId()).map(Job::args).orElse(null);
        if (args == null) {
            retryCheckpoints.drop(active.jobId());
            return;
        }
        if (RetryResume.decide(state.isPresent(), crashed, commitsWorldEffects.test(tool)) == RetryResume.Action.ASK) {
            // The attempt may already have changed the world without recording it: the next one waits for the owner.
            retryCheckpoints.keep(active.jobId(), fingerprint(args), null);
            return;
        }
        if (state.isEmpty()) {
            retryCheckpoints.drop(active.jobId());
            return;
        }
        retryCheckpoints.keep(active.jobId(), fingerprint(args), state.get());
    }

    /** The job arguments as saved progress sees them: an edit to them means the checkpoint describes another request. */
    private static String fingerprint(JsonObject args) {
        return args == null ? "" : args.toString();
    }

    /** Supplies special-structure construction through every task context this coordinator creates. */
    public void setStructureBuilder(dev.friendai.task.structure.StructureBuilder builder) {
        this.structureBuilder = builder == null ? dev.friendai.task.structure.StructureBuilder.NONE : builder;
    }

    public int idleRepairAttempts() {
        return idleRepairAttempts;
    }

    public int idleRepairInserted() {
        return idleRepairInserted;
    }

    public void setEventSink(TaskEventSink eventSink) {
        events.sink(eventSink);
    }

    /** Installs the redactor every crash summary uses; the owning session passes the gateway's configured secrets. */
    public void setRedactor(java.util.function.UnaryOperator<String> redactor) {
        activeTasks.setRedactor(redactor);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean paused() {
        return paused || held();
    }

    /**
     * Whether the last thing that happened to the work was an explicit stop order ("stop, that's enough") and no new
     * work has been given since. An unfinished goal does not restart the work on its own while this holds: after
     * "stop" the goal nudge woke the planner a minute later and the companion went back to chopping.
     */
    public boolean stoppedByOrder() {
        return stoppedByOrder;
    }

    /** Called by the stop order itself, never by internal cancellations. */
    public void markStoppedByOrder() {
        stoppedByOrder = true;
    }

    public void suspend() {
        suspended = true;
        holdBody();
    }

    /**
     * Suspending stops the ticks, not the body: a vanilla path keeps ticking in the mob's own AI step, the move control
     * keeps its last wanted point and the walking inputs keep momentum, so a body already walking would finish that
     * step while nobody is watching it. Halt it where it stands and let the task pick up from there.
     */
    private void holdBody() {
        companion.withdrawMovement();
    }

    /**
     * The owner's own pause: the runner stops starting work and stays stopped through a rejoin or a respawn until it
     * is released. Resuming starts whatever was waiting.
     */
    public void setManualPause(boolean paused) {
        manualPause = paused;
        if (paused) {
            suspend();
            inTransition(events::flush);
        } else {
            resume();
        }
    }

    /** Whether the owner is holding the companion still. */
    public boolean manualPause() {
        return manualPause;
    }

    public void resume() {
        inTransition(() -> {
            suspended = manualPause;
            startPendingRestoredTask();
        });
    }

    /**
     * Drops a restored task that waits for the owner, so a stop or dismissal covers it too. The task never started,
     * so it has no slot of its own in the active or frozen task and no queue job.
     */
    public void discardPendingRestored() {
        pendingRestoredTask = null;
        pendingRestoredJobId = 0;
        pendingRestoredElapsedTicks = 0;
        pendingRestoredTimeoutSec = 0;
    }

    private void startPendingRestoredTask() {
        if (suspended) {
            return; // Kept for the next resume: a manual pause must not start what it was holding.
        }
        Task task = pendingRestoredTask;
        long jobId = pendingRestoredJobId;
        int elapsed = pendingRestoredElapsedTicks;
        int timeoutSec = pendingRestoredTimeoutSec;
        if (task == null) return;
        if (jobId == 0 && companion.owner().isEmpty()) {
            // Owner work restored from a save waits for its owner; the offline free-time policy resumes the runner
            // without meaning to start it.
            return;
        }
        pendingRestoredTask = null;
        pendingRestoredJobId = 0;
        pendingRestoredElapsedTicks = 0;
        pendingRestoredTimeoutSec = 0;
        if (jobId == 0) {
            restore(task, elapsed, timeoutSec);
            return;
        }
        Job job = queueDriver.queue().job(jobId).orElse(null);
        if (job == null || job.state() != JobState.RUNNING) return;
        startRestoredTask(task, job, elapsed, timeoutSec);
    }

    @Override
    public boolean suspended() {
        return suspended;
    }

    @Override
    public TaskExecutionPort.Hold acquireCombatHold() {
        return acquireHold(HoldReason.COMBAT);
    }

    @Override
    public TaskExecutionPort.Hold acquireSelfCareHold() {
        return acquireHold(HoldReason.SELF_CARE);
    }

    public ExecutionHold acquireHold(HoldReason reason) {
        boolean first = !holds.held();
        ExecutionHold hold = holds.acquire(reason);
        // The holder takes the body over; a path left running would keep walking under it. Only the first hold does
        // this, so a second holder does not cancel what the first one just authorized.
        if (first) companion.withdrawMovement();
        return hold;
    }

    public boolean held() {
        return holds.held();
    }

    /**
     * Why no work runs, as a stable token the menu translates: the owner's own pause first, then the owner being
     * away, the server-wide switch, and finally a temporary hold such as combat. Empty while work may run.
     */
    public String holdReason() {
        if (manualPause) return "manual_pause";
        if (companion.owner().isEmpty()) return "owner_away";
        if (paused) return "paused";
        HoldReason temporary = holds.first();
        return temporary == null ? "" : temporary.name().toLowerCase(java.util.Locale.ROOT);
    }

    public void notice(String text) {
        events.notice(text);
    }

    public void debug(String text) {
        events.emit(sink -> sink.onDebug(text));
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
        return queueDriver.queue();
    }
    /** Marks a queue plan as free time's own, so its jobs act under the autonomous rules. */
    public void markAutonomousPlan(long planId) {
        queueDriver.markAutonomous(planId);
    }

    public void unmarkAutonomousPlan(long planId) {
        queueDriver.unmarkAutonomous(planId);
    }
    /** True while the running task is a job of a plan free time started. */
    public boolean autonomousActive() {
        return activeTask() != null
                && activeTask().planId() > 0
                && queueDriver.isAutonomous(activeTask().planId());
    }
    /** True while a queued job belongs to the owner rather than to a plan free time started. */
    public boolean ownerWorkQueued() {
        for (Job job : queueDriver.queue().jobs())
            if (!JobQueue.terminal(job) && !queueDriver.isAutonomous(job.planId())) return true;
        return false;
    }

    /**
     * Whether work that can start now is already here: the active task (unless it is only the idle follow), a job
     * already running, or a pending job whose guards, holds, dependencies and plan status let the queue select it.
     * A job that is merely waiting on the world, a retry or the owner's decision is dormant and holds nothing back,
     * so a new order (a due routine) is not suppressed by work that cannot run anyway.
     */
    public boolean readyWorkAhead() {
        if (activeTask() != null && !activeTask().defaultActivity()) return true;
        JobQueue queue = queueDriver.queue();
        if (queue.jobs().stream().anyMatch(job -> job.state() == JobState.RUNNING)) return true;
        return QueuePolicy.next(queue, facts()).isPresent();
    }

    /** The tool that started the running task, empty when nothing runs. */
    public String currentToolName() {
        return activeTask() == null ? "" : activeTask().toolName();
    }

    /**
     * Whether the owner's local hold currently stops this activation's queued job, read through the same policy the
     * scheduler uses. Cheap when no hold exists: the map is usually empty.
     */
    private boolean heldByOwner(ActiveTask task) {
        if (task == null || task.jobId() <= 0) return false;
        JobQueue queue = queueDriver.queue();
        if (!queue.hasHolds()) return false;
        Job job = queue.job(task.jobId()).orElse(null);
        return job != null && QueuePolicy.held(queue, job, facts());
    }

    /** Whether this job is the action currently changing the world, so a pause on it needs an explicit boundary. */
    public boolean runningJobIs(long jobId) {
        ActiveTask active = activeTask();
        return active != null && active.jobId() == jobId && jobId > 0;
    }

    /** Whether any action of this plan is running right now. */
    public boolean runningJobIsInPlan(long planId) {
        ActiveTask active = activeTask();
        return active != null && active.planId() == planId && planId > 0;
    }
    /** Active queue intent, including its durable plan goal when this task belongs to a plan. */
    public Optional<String> currentQueueIntent() {
        if (activeTask() == null || activeTask().jobId() <= 0) return Optional.empty();
        Job job = queueDriver.queue().job(activeTask().jobId()).orElse(null);
        if (job == null) return Optional.empty();
        String goal = job.planId() <= 0
                ? ""
                : queueDriver.queue().plan(job.planId()).map(p -> p.goal()).orElse("");
        return Optional.of(
                goal.isBlank()
                        ? "queue job #" + job.id() + " " + job.tool()
                        : "queue plan " + goal + " (" + job.tool() + ")");
    }

    /**
     * The action the running job's own plan would start next if the job succeeded, or empty when nothing follows
     * it. A structured plan is walked from its committed cursor against the current facts; a flat plan uses the
     * dependency it declared ({@code after}). The queue, the cursor and its facts are only read: evaluating control
     * flow here never advances the plan. A task can use this to tell whether the plan behind it needs its result,
     * which is the existing dependency path and not a second one.
     */
    public Optional<QueuedStep> followingPlannedAction() {
        ActiveTask active = activeTask();
        if (active == null || active.jobId() <= 0 || active.planId() <= 0) return Optional.empty();
        PlanRecord plan = queueDriver.queue().plan(active.planId()).orElse(null);
        if (plan == null) return Optional.empty();
        if (plan.definition() != null && plan.cursor() != null) {
            PlanInterpreter.Transition transition = PlanInterpreter.advance(
                    plan.definition(), plan.cursor(), planFacts(plan), new PlanInterpreter.ActionResult.Succeeded());
            return transition.event() instanceof PlanInterpreter.Event.EmitAction emit
                    ? Optional.of(emit.step())
                    : Optional.empty();
        }
        return queueDriver.queue().jobsOfPlan(active.planId()).stream()
                .filter(job -> !JobQueue.terminal(job) && job.after().contains(active.jobId()))
                .min(java.util.Comparator.comparingLong(Job::order))
                .map(QueueDriver::step);
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
        return queueDriver.queue().active();
    }

    public int pendingJobs() {
        return (int) queueDriver.queue().jobs().stream()
                .filter(j -> j.state() == JobState.PENDING)
                .count();
    }

    public boolean hasWork() {
        return queueDriver.queue().jobCount() > 0
                || (activeTask() != null && !activeTask().defaultActivity());
    }

    /** This companion's loop counters; the debug log writes {@link LoopMetrics#summary()} every twenty minutes. */
    public LoopMetrics loopMetrics() {
        return metrics.metrics();
    }

    public java.util.List<dev.friendai.presentation.companion.QueueRow> queueRows() {
        return queueDriver.queue().jobCount() == 0
                        && queueDriver.queue().plans().stream().noneMatch(p -> p.cursor() != null)
                ? java.util.List.of()
                : dev.friendai.presentation.companion.QueueRows.build(
                        queueDriver.queue(),
                        facts(),
                        activeTask() == null ? null : activeTask().task().describe());
    }

    public String queueSummary() {
        return queueDriver.queue().jobCount() == 0
                        && queueDriver.queue().plans().stream().noneMatch(p -> p.cursor() != null)
                ? current().map(Task::describe).orElse("none")
                : QueueText.summary(
                        queueDriver.queue(),
                        facts(),
                        activeTask() == null ? null : activeTask().task().describe());
    }

    /** Publishes a decision the running task already made; see {@link TaskContext#decision}. */
    public void decision(String event, String reason, java.util.Map<String, String> facts) {
        inTransition(() -> activeTasks.reportDecision(event, reason, facts));
    }

    /** Opens a nested span on the running activation; see {@link TaskContext#beginSpan}. */
    public dev.friendai.execution.log.TaskLogSpan beginSpan(
            LogContext parent, String operation, String reason, java.util.Map<String, String> facts) {
        return activeTasks.beginSpan(parent, operation, reason, facts);
    }

    /** Forwards a task's phase report to the running activation; see {@link TaskContext#progress}. */
    public void progress(dev.friendai.task.TaskPhase phase, int percent, String reason, String detail, long stamp) {
        inTransition(() -> activeTasks.reportPhase(phase, percent, reason, detail, stamp));
    }

    /** The answer to the owner's Retry, with the tool it concerns so a refusal can name it. */
    public record RetryAnswer(RetryPolicy.Outcome outcome, String tool) {
        public RetryAnswer {
            tool = tool == null ? "" : tool;
        }

        public boolean queued() {
            return outcome == RetryPolicy.Outcome.QUEUED;
        }
    }

    /**
     * Queues the last direct job again, unchanged, when {@code jobId} names it and it ended without doing the work.
     * A targeted request carries the id and the status the owner saw, so an older click cannot replay a newer job and
     * a job that succeeded meanwhile is not repeated. The older untargeted form applies the same rules to the
     * remembered job.
     *
     * @return the outcome; only {@link RetryPolicy.Outcome#QUEUED} queued a job
     */
    public RetryAnswer retryLast(long jobId, String expectedOutcome) {
        RetryPolicy.Last last = lastDirect;
        String tool = last == null ? "" : last.tool();
        boolean stillQueued = last != null
                && queueDriver
                        .queue()
                        .job(last.jobId())
                        .filter(job -> !JobQueue.terminal(job))
                        .isPresent();
        // The idle fallback (follow/stay) does not block a retry: enqueueing it replaces that task anyway.
        boolean busy = activeTask() != null && !activeTask().defaultActivity() && !movementOrder(activeTask());
        RetryPolicy.Outcome outcome = RetryPolicy.decide(
                Optional.ofNullable(last), new RetryPolicy.Request(jobId, expectedOutcome), stillQueued, busy);
        if (outcome != RetryPolicy.Outcome.QUEUED) return new RetryAnswer(outcome, tool);
        Started started = lastStarted;
        if (started == null || started.jobId() != last.jobId()) {
            // The job is remembered but its step is not, so there is nothing to replay from this session.
            return new RetryAnswer(RetryPolicy.Outcome.NOTHING, tool);
        }
        long newJobId = enqueue(started.step(), started.factory);
        retryCheckpoints.carry(
                last.jobId(), newJobId, fingerprint(started.step().args()));
        queueDriver.queue().stampProvenance(newJobId, started.context());
        return new RetryAnswer(RetryPolicy.Outcome.QUEUED, tool);
    }

    /** {@code true} when the last direct job was queued again; see {@link #retryLast(long, String)}. */
    public boolean retryLast() {
        return retryLast(0, "").queued();
    }

    /** Remembers which direct job is current, so a Retry click cannot reach a job the owner never saw. */
    private void noteDirectStarted(long jobId, String tool) {
        lastDirect = new RetryPolicy.Last(jobId, tool, RetryPolicy.Ending.RUNNING);
    }

    /** Records how the remembered direct job ended; endings of other jobs leave it alone. */
    void noteDirectEnded(long jobId, RetryPolicy.Ending ending) {
        RetryPolicy.Last last = lastDirect;
        if (last == null || last.jobId() != jobId) return;
        lastDirect = new RetryPolicy.Last(jobId, last.tool(), ending);
    }

    /** A job that ended on its own: a success must not be replayed and a measured remainder changes the answer. */
    private void noteDirectEnded(long jobId, TaskStatus status, boolean retried, Optional<TaskWorkResult> workResult) {
        if (retried) return;
        RetryPolicy.Ending ending;
        if (status == TaskStatus.SUCCESS) ending = RetryPolicy.Ending.SUCCEEDED;
        else if (workResult != null
                && workResult.isPresent()
                && workResult.get().lines().stream().anyMatch(line -> line.verified() > 0))
            ending = RetryPolicy.Ending.PARTIAL;
        else ending = RetryPolicy.Ending.FAILED;
        noteDirectEnded(jobId, ending);
    }

    /** A job stopped from outside, before it could report an outcome. */
    void noteDirectCancelled(long jobId) {
        noteDirectEnded(jobId, RetryPolicy.Ending.FAILED);
    }

    public long enqueue(QueuedStep step, StepFactory factory) {
        return inTransition(() -> enqueueActive(step, factory));
    }

    /** The invocation the runner is inside, so a job queued by a tool call can keep its provenance. */
    public LogContext currentLogContext() {
        return invocationContext;
    }

    /**
     * Runs one synchronous tool call under an explicit diagnostic scope and restores the previous scope afterwards,
     * even when it throws or re-enters. This is runner-local state owned by {@code DiagnosticToolExecutor}; it is not
     * a thread local and queued work never keeps it.
     */
    public ToolResult withLogContext(LogContext context, Supplier<ToolResult> body) {
        LogContext previous = invocationContext;
        invocationContext = context == null ? LogContext.NONE : context;
        try {
            return body.get();
        } finally {
            invocationContext = previous;
        }
    }

    private long enqueueActive(QueuedStep step, StepFactory factory) {
        syncSettings();
        stoppedByOrder = false;
        requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        Job job = queueDriver.enqueue(step);
        if (invocationContext != LogContext.NONE) {
            job = queueDriver.queue().stampProvenance(job.id(), invocationContext);
        }
        queueDriver.addFactory(job.id(), factory);
        noteDirectStarted(job.id(), job.tool());
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
        stoppedByOrder = false;
        if (steps.size() != stepFactories.size())
            throw new IllegalArgumentException("steps and queueDriver.factories differ");
        for (QueuedStep step : steps) requireCreativeAuthorization(step.principal(), step.creativeSensitive());
        JobQueue.Added added = queueDriver.enqueuePlan(goal, steps, options);
        if (invocationContext != LogContext.NONE) {
            for (long jobId : added.jobIds()) queueDriver.queue().stampProvenance(jobId, invocationContext);
        }
        for (int i = 0; i < stepFactories.size(); i++)
            queueDriver.addFactory(added.jobIds().get(i), stepFactories.get(i));
        prepare.accept(added.planId());
        wakeQueue();
        if (activeTask() == null || activeTask().defaultActivity() || movementOrder(activeTask())) startNextQueued();
        return added.planId();
    }

    public long enqueuePlan(
            String goal, PlanDefinition definition, PlanOptions options, Map<String, PlanStepFactory> stepFactories) {
        return enqueuePlan(goal, definition, options, stepFactories, id -> {});
    }

    public long enqueuePlan(
            String goal,
            PlanDefinition definition,
            PlanOptions options,
            Map<String, PlanStepFactory> stepFactories,
            java.util.function.LongConsumer prepare) {
        return inTransition(() -> enqueuePlanActive(goal, definition, options, stepFactories, prepare));
    }

    private long enqueuePlanActive(
            String goal,
            PlanDefinition definition,
            PlanOptions options,
            Map<String, PlanStepFactory> stepFactories,
            java.util.function.LongConsumer prepare) {
        syncSettings();
        stoppedByOrder = false;
        for (PlanNode node : definition.nodes()) validateStructuredAuthorization(node);
        facts();
        // The queue clock, not the world clock: every progress guard of this plan measures the same ticks.
        long startTick = queueDriver.queue().clock();
        long startDistance = queueDriver.totalTravelledDistance();
        long planId = queueDriver.enqueueStructuredPlan(goal, definition, options, startTick, startDistance);
        queueDriver.addStructuredFactories(planId, stepFactories);
        prepare.accept(planId);
        wakeQueue();
        if (activeTask() == null || activeTask().defaultActivity() || movementOrder(activeTask())) startNextQueued();
        return planId;
    }

    public long enqueuePlan(
            PlanDefinition definition, PlanOptions options, Map<String, PlanStepFactory> stepFactories) {
        return enqueuePlan("", definition, options, stepFactories);
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
        queueDriver.resetAfterClear();
        queueDriver.clearFactoryForRemovedJobs();
    }

    public void registerFactory(long jobId, StepFactory factory) {
        queueDriver.addFactory(jobId, factory);
    }
    /** Starts a direct task, freezing an active queue job so it can resume afterwards. */
    public int start(Task task, String toolName) {
        return start(task, toolName, null, false);
    }

    public int start(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        return inTransition(() -> startActive(task, toolName, caller, creativeSensitive));
    }

    private int startActive(Task task, String toolName, Caller caller, boolean creativeSensitive) {
        // New work given after a stop order releases the hold on goal nudges.
        stoppedByOrder = false;
        CreativeActionContext actionContext = actionContext(caller, creativeSensitive);
        requireCreativeAuthorization(actionContext == null ? null : actionContext.principal(), creativeSensitive);
        ActiveTask current = activeTask();
        if (current != null && current.jobId() > 0 && !current.interrupt()) {
            activeTasks.freezeCurrent();
            // A frozen task keeps its own route and move-control command; the new task owns the body from its first
            // tick, so the old one must stop driving it here rather than when it is resumed.
            companion.withdrawMovement();
        } else if (current != null && current.defaultActivity()) clearCurrentSilently();
        else if (current != null) cancelCurrent("replaced by a new task");
        return begin(task, toolName, task.emitsEvents(), false, caller, actionContext, creativeSensitive);
    }

    public void restore(Task task) {
        restore(task, 0, 0);
    }

    public void restore(Task task, int elapsedActiveTicks) {
        restore(task, elapsedActiveTicks, 0);
    }

    /** Restores a task that keeps the active budget and timeout it had before the save. */
    public void restore(Task task, int elapsedActiveTicks, int timeoutSec) {
        beginRestored(task, elapsedActiveTicks, timeoutSec);
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
        } else if (current != null && current.jobId() > 0) {
            activeTasks.freezeCurrent();
            companion.withdrawMovement();
        } else if (current != null && current.defaultActivity()) clearCurrentSilently();
        else if (current != null) cancelCurrent("interrupted by urgent task");
        begin(task, toolName, task.emitsEvents(), true, caller, actionContext, creativeSensitive);
    }

    public void stop(String reason) {
        mutations.stop(reason);
    }

    public void stopWithoutCleanup(String reason) {
        mutations.stopWithoutCleanup(reason);
    }

    /** Cancels stale work during a capability transition without reporting task failures to the brain. */
    public void cancelForCapabilityChange() {
        mutations.cancelForCapabilityChange();
    }

    public void stopAll(String reason) {
        mutations.stopAll(reason);
    }

    public String clearQueue(String reason) {
        return mutations.clearQueue(reason);
    }

    public void cancelJob(long id, String reason) {
        mutations.cancelJob(id, reason);
    }

    public JobQueue.Result skipJob(long id) {
        return mutations.skipJob(id);
    }

    public void cancelPlan(long id, String reason) {
        mutations.cancelPlan(id, reason);
    }

    public Optional<String> stopNamed(String name, String reason) {
        return mutations.stopNamed(name, reason);
    }

    public void tick() {
        long workStart = diagnosticClock.nanoTime();
        reportClockGap();
        refreshMaterialReserve();
        inTransition(this::tickActive);
        queueDriver.rememberTravelled();
        lastTickWorkNanos = Math.max(0L, diagnosticClock.nanoTime() - workStart);
    }

    /**
     * Recomputes what the remaining queued work still needs and hands it to the companion. Transitions and a slow
     * heartbeat only: composing it every tick would scan the queue and the loot tables sixty times a second.
     */
    private void refreshMaterialReserve() {
        int taskId = currentId();
        long now = companion.level().getGameTime();
        if (taskId == materialReserveTaskId && now - materialReserveTick < MATERIAL_RESERVE_INTERVAL) {
            return;
        }
        materialReserveTaskId = taskId;
        materialReserveTick = now;
        companion.setMaterialReserve(composeMaterialReserve());
    }

    /** The plan's remaining steps and the running task's own need, the larger of the two per item. */
    private dev.friendai.foundation.spatial.MaterialReserve composeMaterialReserve() {
        java.util.List<QueuedStep> remaining = new java.util.ArrayList<>();
        for (Job job : queueDriver.queue().jobs()) {
            if (job.state() == JobState.DONE
                    || job.state() == JobState.FAILED
                    || job.state() == JobState.SKIPPED
                    || job.state() == JobState.CANCELLED) {
                continue;
            }
            remaining.add(new QueuedStep(job.tool(), job.args(), null, null, null, null, 1, null));
        }
        boolean hasLevel = companion.level() instanceof ServerLevel;
        java.util.Map<String, Integer> planNeeds = dev.friendai.execution.queue.PlanMaterials.needs(
                remaining,
                hasLevel
                        ? dev.friendai.world.movement.PlanMaterialSource.forLevel((ServerLevel) companion.level())
                        : null);
        // A step that names bread will bake it from wheat: the ingredients it consumes are committed too, so a
        // reflex or another task cannot spend them before the step runs. One recipe level; the named output is
        // already in the plan share above.
        planNeeds = withIngredients(planNeeds, remaining, hasLevel ? (ServerLevel) companion.level() : null);
        java.util.Map<String, Integer> taskNeeds = java.util.Map.of();
        java.util.Map<String, Integer> travelNeeds = java.util.Map.of();
        ActiveTask active = activeTask();
        if (active != null) {
            taskNeeds = active.task().materialNeeds(context());
            travelNeeds = active.task().travelMaterialNeeds(context());
        }
        dev.friendai.foundation.spatial.MaterialReserve composed =
                dev.friendai.foundation.spatial.MaterialReserve.compose(planNeeds, taskNeeds, travelNeeds);
        if (!(companion.level() instanceof ServerLevel level) || composed.isEmpty()) {
            return composed;
        }
        // A task names the block it is sent for; what that block yields is what the tool needs.
        var source = dev.friendai.world.movement.PlanMaterialSource.forLevel(level);
        java.util.Map<String, Integer> workWithDrops = withDrops(source, composed.workAsMap());
        java.util.Map<String, Integer> travelWithDrops = withDrops(source, composed.travelAsMap());
        return dev.friendai.foundation.spatial.MaterialReserve.compose(
                workWithDrops, java.util.Map.of(), travelWithDrops);
    }

    /** Adds the carried ingredients the remaining steps will consume to the plan's own named needs. */
    private java.util.Map<String, Integer> withIngredients(
            java.util.Map<String, Integer> planNeeds, java.util.List<QueuedStep> remaining, ServerLevel level) {
        if (level == null || remaining.isEmpty()) {
            return planNeeds;
        }
        java.util.Set<String> carried = new java.util.HashSet<>();
        for (int slot = 0; slot < companion.inventory().getContainerSize(); slot++) {
            var stack = companion.inventory().getItem(slot);
            if (!stack.isEmpty()) {
                carried.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem())
                        .toString());
            }
        }
        if (!companion.getMainHandItem().isEmpty()) {
            carried.add(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(companion.getMainHandItem().getItem())
                    .toString());
        }
        if (carried.isEmpty()) {
            return planNeeds;
        }
        java.util.Map<String, Integer> ingredients;
        try {
            ingredients = dev.friendai.features.crafting.PlanIngredients.committed(
                    remaining, dev.friendai.features.crafting.minecraft.RecipeIndex.of(level), carried);
        } catch (RuntimeException unavailable) {
            // A reserve is an optimisation of safety, not a gate: a recipe index that cannot be read leaves the
            // named needs as they are rather than failing the tick.
            return planNeeds;
        }
        if (ingredients.isEmpty()) {
            return planNeeds;
        }
        java.util.Map<String, Integer> merged = new java.util.LinkedHashMap<>(planNeeds);
        ingredients.forEach((id, count) -> merged.merge(
                id, count, (named, ingredient) -> (int) Math.min(Integer.MAX_VALUE, (long) named + ingredient)));
        return merged;
    }

    private static java.util.Map<String, Integer> withDrops(
            dev.friendai.world.movement.PlanMaterialSource source, java.util.Map<String, Integer> needs) {
        java.util.Map<String, Integer> expanded = new java.util.LinkedHashMap<>(needs);
        for (java.util.Map.Entry<String, Integer> entry : needs.entrySet()) {
            for (String item : source.itemsFor(entry.getKey())) {
                expanded.merge(item, entry.getValue(), Math::max);
            }
        }
        return expanded;
    }

    /** One clock.gap per observed gap over a second; the reason stays unknown, it is never inferred as a pause. */
    private void reportClockGap() {
        long now = diagnosticClock.wallMillis();
        if (lastTickWall > 0L) {
            long gap = now - lastTickWall;
            if (gap > GAP_THRESHOLD_MS && !clockGapReported) {
                clockGapReported = true;
                ActiveTask interrupted = activeTask();
                if (interrupted == null) interrupted = frozenTask();
                long workMs = lastTickWorkNanos / 1_000_000L;
                String reason = gapReason(gap, workMs);
                Map<String, String> facts = new java.util.LinkedHashMap<>();
                facts.put("workMs", Long.toString(workMs));
                TaskLogEvent event;
                if (interrupted == null) {
                    event = TaskLogEvents.clockGap(LogContext.NONE, gap, reason, facts);
                } else {
                    String phase = interrupted.task().debugStatus();
                    if (phase == null || phase.isBlank())
                        phase = interrupted.task().describe();
                    ExecutionTiming.Snapshot measured = interrupted.timing().snapshot();
                    facts.put("tool", interrupted.toolName());
                    facts.put("task", Integer.toString(interrupted.id()));
                    facts.put("plan", Long.toString(interrupted.planId()));
                    facts.put("job", Long.toString(interrupted.jobId()));
                    facts.put("phase", phase == null ? "" : phase);
                    facts.put("taskTickMs", Long.toString(measured.tickNanos() / 1_000_000L));
                    facts.put("activeTicks", Long.toString(measured.activeTicks()));
                    facts.put("heldTicks", Long.toString(measured.heldTicks()));
                    event = TaskLogEvents.clockGap(interrupted.logContext(), gap, reason, facts);
                }
                emit(sink -> sink.onDiagnostic(event));
            } else if (gap <= GAP_THRESHOLD_MS) {
                clockGapReported = false;
            }
        }
        lastTickWall = now;
    }

    /**
     * What the runner can actually prove about a gap. Its own measured tick covers the interval only when that tick
     * was long enough to explain it; anything else — a paused integrated server, a process freeze, a wall-clock jump —
     * leaves no evidence in this tick, so it stays {@code unknown} instead of being blamed on the task.
     */
    private static String gapReason(long gapMs, long workMs) {
        if (workMs < MIN_ATTRIBUTABLE_WORK_MS) return "unknown";
        return workMs + GAP_ATTRIBUTION_SLACK_MS >= gapMs ? "slow-tick" : "unknown";
    }

    public void releaseAllHolds() {
        holds.closeAll();
    }

    private void tickActive() {
        syncSettings();
        revalidateCreativeHover();
        clearIssuedInterrupt();
        if (paused || suspended || held()) {
            if (activeTasks.currentActive() != null || activeTasks.frozenActive() != null) {
                activeTasks.recordHoldTick();
            }
            return;
        }
        if (activeTask() != null && activeTask().defaultActivity() && !mayFollowOwner.getAsBoolean())
            clearCurrentSilently();
        queueDriver.advanceClock();
        reconcileRepairs();
        expireDeadlines();
        boolean hasStructuredPlan = false;
        for (PlanRecord plan : queueDriver.queue().plans())
            if (plan.cursor() != null && !plan.cursor().stack().isEmpty()) {
                hasStructuredPlan = true;
                break;
            }
        if (hasStructuredPlan) {
            queueDriver.refreshFacts(companion);
        }
        if (activeTask() == null) {
            if (frozenTask() != null) {
                if (heldByOwner(frozenTask())) pollQueue();
                else resumeFrozen();
            } else if (!pollQueue()) startDefault();
            return;
        }
        if ((activeTask().defaultActivity() || movementOrder(activeTask())) && pollQueue()) return;
        ActiveTask active = activeTask();
        if (active.planId() > 0)
            queueDriver
                    .queue()
                    .plan(active.planId())
                    .filter(p -> p.cursor() != null)
                    .ifPresent(this::planFacts);
        // A pause or deferral of the work that is already running stops it at its next tick boundary, so no further
        // placement, withdrawal or block change happens under a hold the owner has already asked for.
        if (heldByOwner(active)) {
            activeTasks.freezeCurrent();
            activeTasks.recordHoldTick();
            companion.withdrawMovement();
            resumeFrozen();
            return;
        }
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
            completeActive(
                    result.active(),
                    result.status(),
                    result.disposition(),
                    result.summary(),
                    result.workResult(),
                    result.crashed());
        } else if (activeTask() == active) maybeNoticeBlocked();
    }

    /**
     * Records how long an issued interrupt waited once nothing is running or queued. Runs every tick, so it moves a
     * long-blocked stop onto the interrupt counter only when the wait really ended; no allocation either way.
     */
    private void clearIssuedInterrupt() {
        if (!metrics.interruptPending() || hasWork()) return;
        metrics.interruptCleared(companion.level().getGameTime());
    }

    private void revalidateCreativeHover() {
        if (!companion.creativeMode() || companion.creativeLanding() || !companion.isNoGravity()) return;
        if (currentCreativeActionAllowed(CreativeAction.FLIGHT)
                || CreativeModeService.ownerMayUsePassiveFlight(companion)) return;
        companion.endCreativeFlight(false);
        companion.getNavigation().stop();
    }

    private void completeActive(
            ActiveTask active,
            TaskStatus status,
            TaskDisposition disposition,
            String summary,
            Optional<TaskWorkResult> workResult,
            boolean crashed) {
        String result = summary == null ? active.task().resultSummary() : summary;
        // The face answers what the work cost: a finished job pulses pleased, a failed one displeased. Danger and
        // combat
        // still win inside the model, and a cancellation is not an outcome, so it never colours the face.
        companion.noteTaskOutcome(status == TaskStatus.SUCCESS);
        // One place per finished task: a thing the companion built, farmed or mined is worth remembering itself.
        if (status == TaskStatus.SUCCESS && events.hasSink()) {
            PlaceCandidate candidate = active.task().placeCandidate();
            if (candidate != null) emit(sink -> sink.onPlace(candidate, active.toolName()));
        }
        if (active.interrupt()) {
            if (active.jobId() > 0 && active.planId() == 0) noteDirectCancelled(active.jobId());
            retryCheckpoints.drop(active.jobId());
            if (active.emit())
                emit(sink -> sink.onFinished(
                        active.id(),
                        active.planId(),
                        active.toolName(),
                        disposition,
                        result,
                        workResult,
                        active.execution()));
            resumeFrozen();
            return;
        }
        if (active.jobId() > 0) {
            boolean retried = false;
            if (status == TaskStatus.SUCCESS) queueDriver.finish(active.jobId(), result);
            else retried = queueDriver.fail(active.jobId(), result).retried();
            // The failure keeps the job pending only when it may run again: that is the moment its progress matters.
            if (retried) rememberRetry(active, crashed);
            else retryCheckpoints.drop(active.jobId());
            if (active.planId() == 0) noteDirectEnded(active.jobId(), status, retried, workResult);
            if (!retried)
                emit(sink -> sink.onJobFinished(
                        active.jobId(),
                        active.planId(),
                        active.toolName(),
                        disposition,
                        result,
                        active.execution(),
                        workResult));
            if (active.planId() == 0) {
                if (!retried) {
                    if (active.task().emitsEvents())
                        emit(sink -> sink.onFinished(
                                active.id(),
                                active.planId(),
                                active.toolName(),
                                disposition,
                                result,
                                workResult,
                                active.execution()));
                    queueDriver.remove(active.jobId());
                }
            } else if (isStructuredPlan(active.planId())) {
                if (!retried) {
                    if (status == TaskStatus.SUCCESS)
                        queueDriver.advanceStructuredPlan(
                                active.planId(), new PlanInterpreter.ActionResult.Succeeded());
                    else
                        handleStructuredActionFailure(active.planId(), active.jobId(), result, disposition, workResult);
                }
            } else if (queueDriver.queue().planStatus(active.planId()) == PlanStatus.NEEDS_DECISION) {
                queueDriver.markAwaitingDecision(active.planId());
                queueDriver.notifyBlocked(active.planId(), active.jobId(), result, workResult);
            } else if (queueDriver.queue().planStatus(active.planId()) == PlanStatus.DONE
                    || queueDriver.queue().planStatus(active.planId()) == PlanStatus.FAILED)
                queueDriver.finishPlan(
                        active.planId(),
                        QueueText.planSummary(queueDriver.queue(), active.planId()),
                        queueDriver.queue().planStatus(active.planId()) == PlanStatus.DONE
                                ? TaskDisposition.SUCCESS
                                : disposition,
                        workResult);
        } else if (active.emit())
            emit(sink -> sink.onFinished(
                    active.id(),
                    active.planId(),
                    active.toolName(),
                    disposition,
                    result,
                    workResult,
                    active.execution()));
        if (frozenTask() != null) {
            resumeFrozen();
            return;
        }
        if (!startNextQueued()) startDefault();
    }

    int begin(Task task, String name, boolean emit, boolean interrupt) {
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
        return begin(task, name, emit, interrupt, caller, actionContext, creativeSensitive, 0, 0);
    }

    /** Starts a restored task whose activation keeps the active budget it already spent before the save. */
    private int beginRestored(Task task, int elapsedActiveTicks, int timeoutSec) {
        return begin(task, task.type(), false, false, null, null, false, elapsedActiveTicks, timeoutSec);
    }

    private int begin(
            Task task,
            String name,
            boolean emit,
            boolean interrupt,
            Caller caller,
            CreativeActionContext actionContext,
            boolean creativeSensitive,
            int elapsedActiveTicks,
            int timeoutSec) {
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
                baseContext(),
                invocationContext,
                "command");
        try {
            return activeTasks.start(task, start, elapsedActiveTicks, timeoutSec);
        } catch (RuntimeException e) {
            LOGGER.warn("Task {} failed to start", name, e);
            if (emit) emit(sink -> sink.onFinished(start.id(), name, TaskStatus.FAILED, activeTasks.crashSummary(e)));
            if (interrupt) resumeFrozen();
            else startNextOrDefault();
            return start.id();
        }
    }

    void cancelCurrent(String reason) {
        ActiveTask a = activeTask();
        if (a == null) return;
        activeTasks.stop(new StopRequest(reason), baseContext());
        if (a.jobId() > 0) {
            queueDriver.cancelJob(a.jobId());
            if (a.planId() == 0) noteDirectCancelled(a.jobId());
            emit(sink -> sink.onJobFinished(
                    a.jobId(), a.planId(), a.toolName(), TaskDisposition.CANCELLED, reason, a.execution()));
            if (a.planId() == 0 && a.emit())
                emit(sink -> sink.onCancelled(a.id(), a.execution(), a.toolName(), reason));
        } else if (a.emit()) emit(sink -> sink.onCancelled(a.id(), a.execution(), a.toolName(), reason));
    }

    private void clearCurrentSilently() {
        activeTasks.stop(new StopRequest("cleared without cancellation event"), baseContext());
    }
    /** Idle-time start attempt, throttled so a blocked queue is not re-scanned every tick. */
    private boolean pollQueue() {
        if ((queueDriver.queue().jobCount() == 0
                        && queueDriver.queue().plans().stream().noneMatch(plan -> plan.cursor() != null))
                || !queueDriver.pollDue()) return false;
        boolean started = startNextQueued();
        if (!started) queueDriver.deferNextPoll(POLL_INTERVAL_TICKS);
        return started;
    }

    GuardFacts facts() {
        return queueDriver.refreshFacts(companion);
    }

    GuardFacts planFacts(PlanRecord plan) {
        return queueDriver.refreshPlanFacts(companion, plan);
    }

    TaskContext queueTaskContext(CreativeActionContext actionContext) {
        return context(settingsSupplier.get(), actionContext);
    }

    ServerSettings settings() {
        return settingsSupplier.get();
    }

    boolean isStructuredPlan(long planId) {
        return queueDriver.isStructuredPlan(planId);
    }

    private boolean startNextQueued() {
        return startNextQueued(0);
    }

    /**
     * One scheduler step settles at most {@link #MAX_ACTIVATION_SETTLEMENTS} refusing jobs; the rest wait for the next
     * poll. A queue full of jobs that cannot start must not keep one tick busy.
     */
    private boolean startNextQueued(int settled) {
        if (settled >= MAX_ACTIVATION_SETTLEMENTS) {
            queueDriver.deferNextPoll(POLL_INTERVAL_TICKS);
            return false;
        }
        if ((queueDriver.queue().jobCount() == 0
                        && queueDriver.queue().plans().stream().noneMatch(plan -> plan.cursor() != null))
                || paused
                || suspended
                || held()) return false;
        expireDeadlines();
        Optional<Job> candidate = queueDriver.nextReady();
        if (candidate.isEmpty()) {
            if (tryIdleRepair()) return startNextQueued(settled + 1);
            maybeNoticeBlocked();
            return false;
        }
        if (activeTask() != null && activeTask().defaultActivity()) clearCurrentSilently();
        else if (activeTask() != null && movementOrder(activeTask())) clearCurrentSilently();
        else if (activeTask() != null) return false;
        Job job = candidate.get();
        StepFactory factory = queueDriver.factory(job.id());
        if (factory == null) {
            // A factory that is not registered cannot appear later, so the job ends on its first attempt.
            String reason = "task factory is unavailable for " + job.tool();
            queueDriver.failPermanently(job.id(), reason);
            refuseActivation(job, reason, TaskDisposition.FAILED);
            if (isStructuredPlan(job.planId())) {
                queueDriver.cancelPlan(job.planId());
                queueDriver.finishPlan(job.planId(), reason, false);
            } else if (job.planId() > 0) {
                queueDriver.notifyBlocked(job.planId(), job.id(), reason);
            }
            return startNextQueued(settled + 1);
        }
        CreativeActionContext actionContext = actionContext(job.principal(), job.creativeSensitive());
        if (companion.creativeMode()
                && job.creativeSensitive()
                && !CreativeModeService.isActionAuthorized(
                        companion, actionContext, CreativeAction.WORLD_CHANGING_TASK)) {
            cancelForCapabilityChange();
            return false;
        }
        TaskContext authorizationContext = new TaskContext(
                companion, (ServerLevel) companion.level(), this, actionContext, settingsSupplier.get());
        String revoked = "access to " + job.tool() + " was revoked before the action started";
        if (isStructuredPlan(job.planId())) {
            PlanStepFactory planFactory = queueDriver.planFactory(job.planId(), job.tool());
            if (planFactory == null || !planFactory.authorized(authorizationContext, step(job))) {
                queueDriver.cancelPlan(job.planId());
                refuseActivation(job, revoked, TaskDisposition.CANCELLED);
                queueDriver.finishPlan(job.planId(), revoked, false);
                return startNextQueued(settled + 1);
            }
        } else if (!factory.authorized(authorizationContext)) {
            // Deterministic refusal: retrying with the same principal would only fail again.
            queueDriver.cancelJob(job.id(), revoked);
            refuseActivation(job, revoked, TaskDisposition.CANCELLED);
            if (job.planId() > 0) queueDriver.finishPlan(job.planId(), revoked, false);
            return startNextQueued(settled + 1);
        }
        if (job.planId() == 0) {
            lastStarted = new Started(job.id(), step(job), factory, job.diagnostic());
            noteDirectStarted(job.id(), job.tool());
        }
        // The previous attempt may have committed part of its work: continue from its checkpoint, never repeat it.
        Optional<RetryCheckpoints.Entry> pending = retryCheckpoints.take(job.id(), fingerprint(job.args()));
        Task resumed = null;
        if (pending.isPresent()) {
            boolean committing = commitsWorldEffects.test(job.tool());
            if (pending.get().resumable() && retryRestore != null)
                resumed = retryRestore
                        .restore(pending.get().state(), authorizationContext)
                        .orElse(null);
            if (resumed == null && RetryResume.unreadable(committing) == RetryResume.Action.ASK) {
                // The saved progress cannot be read, so a repeat could apply its committed effects twice.
                String blocked = Job.UNREADABLE_PROGRESS_RESULT;
                queueDriver.cancelJob(job.id(), blocked);
                refuseActivation(job, blocked, TaskDisposition.CANCELLED);
                if (isStructuredPlan(job.planId()))
                    handleStructuredActionFailure(job.planId(), job.id(), blocked, TaskDisposition.CANCELLED);
                return startNextQueued(settled + 1);
            }
        }
        try {
            ServerSettings settings = settingsSupplier.get();
            TaskContext activationContext =
                    new TaskContext(companion, (ServerLevel) companion.level(), this, actionContext, settings);
            Task task = resumed != null ? resumed : factory.create(activationContext);
            queueDriver.markRunning(job.id());
            queueDriver.removeAwaitingDecision(job.planId());
            activeTasks.start(
                    task,
                    new TaskStartContext(
                            job.tool(),
                            callerFor(job.principal()),
                            actionContext,
                            true,
                            false,
                            false,
                            (int) job.id(),
                            job.id(),
                            job.planId(),
                            job.creativeSensitive(),
                            settings,
                            activationContext,
                            job.diagnostic(),
                            resumed == null ? "activation" : "resume"));
            queueDriver.resetBlockedClock();
            return true;
        } catch (RuntimeException e) {
            String reason = e instanceof IllegalArgumentException ? e.getMessage() : activeTasks.crashSummary(e);
            var failed = queueDriver.fail(job.id(), reason);
            if (!failed.retried()) {
                // A failed start is a refusal nobody else publishes: the task never ran a tick, so the running-task
                // path has no outcome for it and the retry button has nothing to replay.
                refuseActivation(job, reason, TaskDisposition.FAILED);
            }
            if (!failed.retried() && isStructuredPlan(job.planId()))
                handleStructuredActionFailure(job.planId(), job.id(), reason);
            else if (!failed.retried() && job.planId() > 0) {
                if (queueDriver.queue().planStatus(job.planId()) == PlanStatus.NEEDS_DECISION) {
                    queueDriver.markAwaitingDecision(job.planId());
                    queueDriver.notifyBlocked(job.planId(), job.id(), reason);
                } else if (queueDriver.queue().planStatus(job.planId()) == PlanStatus.FAILED)
                    queueDriver.finishPlan(job.planId(), reason, false);
            }
            return startNextQueued(settled + 1);
        }
    }

    /** Activation refusals one scheduler step may settle before the rest wait for the next poll. */
    private static final int MAX_ACTIVATION_SETTLEMENTS = 8;

    /**
     * One terminal outcome for a queued job that never produced a running task, through the same sink a finished task
     * uses. Without it a refusal that arrives after a tool already returned the job id leaves no history row and never
     * reaches the model. A direct job has no plan boundary to carry it, so its row is the only trace and the job leaves
     * the queue right away.
     */
    private void refuseActivation(Job job, String reason, TaskDisposition disposition) {
        String bounded = QueueText.truncate(reason, QueueText.MAX_BLOCKED_REASON);
        emit(sink ->
                sink.onJobFinished(job.id(), job.planId(), job.tool(), disposition, bounded, 0L, Optional.empty()));
        if (job.planId() == 0) {
            noteDirectEnded(job.id(), RetryPolicy.Ending.REFUSED);
            emit(sink -> sink.onFinished((int) job.id(), 0L, job.tool(), disposition, bounded, Optional.empty(), 0L));
            queueDriver.remove(job.id());
        }
    }

    private void handleStructuredActionFailure(long planId, long jobId, String reason) {
        queueDriver.handleStructuredActionFailure(planId, jobId, reason);
    }

    private void handleStructuredActionFailure(long planId, long jobId, String reason, TaskDisposition disposition) {
        queueDriver.handleStructuredActionFailure(planId, jobId, reason, disposition);
    }

    private void handleStructuredActionFailure(
            long planId, long jobId, String reason, TaskDisposition disposition, Optional<TaskWorkResult> workResult) {
        queueDriver.handleStructuredActionFailure(planId, jobId, reason, disposition, workResult);
    }

    private static QueuedStep step(Job job) {
        return QueueDriver.step(job);
    }

    private void maybeNoticeBlocked() {
        queueDriver.maybeNoticeBlocked(
                facts(),
                settingsSupplier.get().tasks().queueBlockedNoticeSec() * 20,
                activeTask() != null && activeTask().jobId() > 0);
    }

    void publishPlanBlocked(
            long plan,
            String goal,
            long jobId,
            String tool,
            String reason,
            int remaining,
            boolean remainingExact,
            Optional<TaskWorkResult> workResult) {
        emit(sink -> sink.onPlanBlocked(plan, goal, jobId, tool, reason, remaining, remainingExact, workResult));
    }

    void publishPlanFinished(
            long id, String goal, TaskDisposition disposition, String summary, Optional<TaskWorkResult> workResult) {
        emit(sink -> sink.onPlanFinished(id, goal, disposition, summary, workResult));
    }

    private void expireDeadlines() {
        for (long id : queueDriver.expiredPlans()) {
            ActiveTask a = activeTask();
            ActiveTask frozen = frozenTask();
            StopRequest deadline = new StopRequest("ran out of time");
            if (frozen != null && frozen.planId() == id) {
                publishPlanTimeout(activeTasks.timeoutFrozen(deadline, context(frozen)));
            }
            if (a != null && a.planId() == id) {
                Task cleanup = a.task().cleanupAfterCancel(context());
                activeTasks.timeout(deadline, context(a)).ifPresent(this::publishPlanTimeout);
                queueDriver.cancelPlanAndFinish(id, "ran out of time", TaskDisposition.TIMED_OUT);
                if (cleanup != null) begin(cleanup, "scaffold_cleanup", false, false);
                continue;
            }
            queueDriver.cancelPlanAndFinish(id, "ran out of time", TaskDisposition.TIMED_OUT);
        }
    }

    private void publishPlanTimeout(ActiveTaskController.Completion completion) {
        ActiveTask expired = completion.active();
        queueDriver.failPermanently(expired.jobId(), completion.summary());
        emit(sink -> sink.onJobFinished(
                expired.jobId(),
                expired.planId(),
                expired.toolName(),
                completion.disposition(),
                completion.summary(),
                expired.execution(),
                completion.workResult()));
    }

    void publishQueuedCancellation(Job job, String reason) {
        if (job == null) return;
        emit(sink -> sink.onDiagnostic(TaskLogEvents.queuedCancelEvent(job.diagnostic(), job.tool(), reason)));
        if (job.planId() == 0) {
            noteDirectCancelled(job.id());
            emit(sink -> sink.onCancelled((int) job.id(), 0, job.tool(), reason));
        } else {
            emit(sink -> sink.onJobFinished(job.id(), job.planId(), job.tool(), TaskDisposition.CANCELLED, reason, 0));
        }
    }

    void resumeFrozen() {
        ActiveTask old = frozenTask();
        Job frozenJob =
                old == null ? null : queueDriver.queue().job(old.jobId()).orElse(null);
        if (old != null && frozenJob != null && frozenJob.state() == JobState.RUNNING) {
            if (!stillAuthorized(frozenJob)) {
                // Revoked work is settled here rather than resumed: a preempting task must not hand the grant back.
                mutations.cancelJob(old.jobId(), REVOKED);
                startNextOrDefault();
                return;
            }
            if (QueuePolicy.held(queueDriver.queue(), frozenJob, facts())) {
                // The owner parked this action; it keeps its place until the hold is released, and unrelated ready
                // work may use the body meanwhile.
                activeTasks.recordHoldTick();
                if (activeTask() == null) startNextQueued();
                return;
            }
            activeTasks.takeFrozen();
            activeTasks.resumeFrozen(old);
        } else {
            if (old != null) {
                if (old.planId() == 0) noteDirectCancelled(old.jobId());
                stopFrozen("frozen task is no longer queued");
            }
            startNextOrDefault();
        }
    }

    /**
     * Rechecks the grant behind running, frozen and restored work after a trust change, so work admitted under the old
     * grant cannot keep changing the world. Work that keeps its grant is untouched and the queue carries on.
     */
    public void revalidateAuthority() {
        inTransition(() -> {
            for (ActiveTask task : java.util.Arrays.asList(activeTask(), frozenTask())) {
                if (task == null || task.jobId() == 0) continue;
                Job job = queueDriver.queue().job(task.jobId()).orElse(null);
                if (job != null && !stillAuthorized(job)) mutations.cancelJob(task.jobId(), REVOKED);
            }
            if (pendingRestoredTask != null && pendingRestoredJobId > 0) {
                Job job = queueDriver.queue().job(pendingRestoredJobId).orElse(null);
                if (job == null || !stillAuthorized(job)) {
                    long jobId = pendingRestoredJobId;
                    discardPendingRestored();
                    if (job != null) mutations.cancelJob(jobId, REVOKED);
                }
            }
            if (activeTask() == null) startNextOrDefault();
        });
    }

    /**
     * Whether a queued job's principal may still start or continue it, asked through the same factory the activation
     * boundary uses: the answer comes from the live trust book, never from the grant saved with the job.
     */
    private boolean stillAuthorized(Job job) {
        StepFactory factory = queueDriver.factory(job.id());
        // No factory means no step of this shape can be built at all; the activation boundary reports that itself.
        if (factory == null) return true;
        TaskContext context = new TaskContext(
                companion,
                (ServerLevel) companion.level(),
                this,
                actionContext(job.principal(), job.creativeSensitive()),
                settingsSupplier.get());
        try {
            if (isStructuredPlan(job.planId())) {
                PlanStepFactory planFactory = queueDriver.planFactory(job.planId(), job.tool());
                return planFactory != null && planFactory.authorized(context, step(job));
            }
            return factory.authorized(context);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    void startNextOrDefault() {
        if (activeTask() != null) return;
        if (frozenTask() != null) {
            resumeFrozen();
            return;
        }
        if (!startNextQueued()) startDefault();
    }

    Task combineCleanup(Task first, Task second) {
        if (first == null) return second;
        if (second == null) return first;
        return new SequenceTask(
                "scaffold cleanup",
                List.of(
                        new PlanEntry("scaffold_cleanup", ctx -> first),
                        new PlanEntry("scaffold_cleanup", ctx -> second)),
                () -> timeoutSec.getAsInt() * 20);
    }

    private boolean tryIdleRepair() {
        if (repairFactory == null) return false;
        idleRepairAttempts++;
        var queue = queueDriver.queue();
        var jobs = queue.jobs();
        java.util.Set<Long> consent = new java.util.HashSet<>();
        for (Job job : jobs) {
            if (job.planId() > 0) {
                queue.plan(job.planId()).filter(plan -> plan.autoRepair()).ifPresent(plan -> consent.add(plan.id()));
            }
        }
        // Consent is permission to try a remedy within the requester's existing authority: a plan owned by a player
        // who may no longer use the repair tool gets no gathering work started on their behalf, and its blocked step
        // is skipped rather than hiding an eligible one further down the queue.
        java.util.Map<Long, Optional<RepairPlan>> admitted = new java.util.HashMap<>();
        var selected = IdleRepairPolicy.select(
                queue,
                facts(),
                java.util.Set.of(IdleRepairPolicy.REMEDY_TOOL),
                consent,
                REPAIR_TOOLS,
                repair -> repair.prerequisite() instanceof dev.friendai.execution.queue.PlanRepair.Item candidate
                        && admitted.computeIfAbsent(
                                        repair.blocked().id(),
                                        ignored -> Optional.ofNullable(admit(repair.blocked(), argsFor(candidate))))
                                .isPresent());
        if (selected.isEmpty()) return false;
        var repair = selected.get();
        if (!(repair.prerequisite() instanceof dev.friendai.execution.queue.PlanRepair.Item item)) return false;
        Job blocked = repair.blocked();
        RepairPlan plan = admitted.get(blocked.id()).orElseThrow();
        JsonObject args = argsFor(item);
        String key = blocked.id() + ":" + item.item();
        if (repairRequests.size() >= MAX_TRACKED_REPAIRS) pruneRepairRequests(facts());
        if (!repairRequests.add(key)) return false;
        var step = new dev.friendai.execution.queue.QueuedStep(
                dev.friendai.execution.queue.IdleRepairPolicy.REMEDY_TOOL,
                args,
                blocked.priority(),
                java.util.List.of(),
                dev.friendai.execution.queue.GuardAction.WAIT,
                dev.friendai.execution.queue.FailurePolicy.ASK,
                1,
                java.util.List.of(),
                blocked.principal(),
                plan.creativeSensitive());
        var result = queue.insertPrerequisite(blocked.id(), step);
        if (!result.ok()) {
            repairRequests.remove(key);
            return false;
        }
        long id = queue.jobs().stream().mapToLong(Job::id).max().orElse(-1);
        if (id < 0) {
            repairRequests.remove(key);
            return false;
        }
        queueDriver.addFactory(id, plan.factory());
        repairs.put(id, new RepairTarget(blocked.id(), item.item(), false));
        if (repairs.size() > MAX_TRACKED_REPAIRS) {
            var oldest = repairs.keySet().iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        idleRepairInserted++;
        return true;
    }

    private static JsonObject argsFor(dev.friendai.execution.queue.PlanRepair.Item item) {
        JsonObject args = new JsonObject();
        args.addProperty("item", item.item());
        args.addProperty("count", item.count());
        return args;
    }

    /**
     * Builds the remedy through the registered tool admission and the activation gates a queued step passes, or
     * returns null when the requester may no longer run it.
     */
    private RepairPlan admit(Job blocked, JsonObject args) {
        if (repairFactory == null) return null;
        var admitted = repairFactory.forCaller(blocked.principal(), args.deepCopy());
        if (admitted.isEmpty()) return null;
        RepairPlan plan = admitted.get();
        // The same gates a step the owner queued passes: current access, then the creative grant it declares.
        CreativeActionContext actionContext = actionContext(blocked.principal(), plan.creativeSensitive());
        if (!plan.factory().authorized(queueTaskContext(actionContext))) return null;
        try {
            requireCreativeAuthorization(blocked.principal(), plan.creativeSensitive());
        } catch (RuntimeException refused) {
            return null;
        }
        return plan;
    }

    /** One automatic remedy, the blocked step it exists for, and whether its hold was mirrored onto the remedy. */
    private record RepairTarget(long blocked, String item, boolean inheritedHold) {}

    /** The once-per-blocked-fact memory is bounded; a stale entry is dropped when its step moved on. */
    private void pruneRepairRequests(GuardFacts facts) {
        repairRequests.removeIf(key -> {
            int colon = key.indexOf(':');
            if (colon <= 0) return true;
            Job blocked = queueDriver
                    .queue()
                    .job(Long.parseLong(key.substring(0, colon)))
                    .orElse(null);
            return blocked == null
                    || blocked.state() != JobState.PENDING
                    || !stillNeeds(blocked, key.substring(colon + 1), facts);
        });
    }

    /**
     * Keeps a remedy tied to the step that needs it. When that step is gone or settled the remedy is cancelled through
     * the ordinary job path, so a running task stops at its own boundary. When the owner holds the step, the same hold
     * is mirrored onto the remedy: the gathering work freezes at the boundary a hand-queued step would and resumes on
     * release, instead of carrying on for a plan the owner paused.
     */
    private void reconcileRepairs() {
        if (repairs.isEmpty()) return;
        GuardFacts facts = null;
        java.util.List<Long> cancel = new java.util.ArrayList<>();
        java.util.List<String> reasons = new java.util.ArrayList<>();
        var iterator = repairs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long remedyId = entry.getKey();
            Job remedy = queueDriver.queue().job(remedyId).orElse(null);
            if (remedy == null || JobQueue.terminal(remedy)) {
                iterator.remove();
                continue;
            }
            RepairTarget target = entry.getValue();
            Job blocked = queueDriver.queue().job(target.blocked()).orElse(null);
            if (blocked == null || blocked.state() != JobState.PENDING) {
                cancel.add(remedyId);
                reasons.add("the step it was fetching for is no longer waiting");
                repairRequests.remove(target.blocked() + ":" + target.item());
                iterator.remove();
                continue;
            }
            if (facts == null) facts = facts();
            QueueHold hold = QueuePolicy.blockingHold(queueDriver.queue(), blocked, facts)
                    .orElse(null);
            if (hold != null
                    && !hold.equals(
                            queueDriver.queue().hold(HoldKey.job(remedyId)).orElse(null))) {
                try {
                    queueDriver.queue().setHold(HoldKey.job(remedyId), hold);
                    entry.setValue(new RepairTarget(target.blocked(), target.item(), true));
                } catch (IllegalArgumentException tooManyHolds) {
                    // The hold table is full: stop the remedy rather than let it run for held work.
                    cancel.add(remedyId);
                    reasons.add("the step it was fetching for is held");
                    repairRequests.remove(target.blocked() + ":" + target.item());
                    iterator.remove();
                }
            } else if (hold == null && target.inheritedHold()) {
                queueDriver.queue().release(HoldKey.job(remedyId));
                entry.setValue(new RepairTarget(target.blocked(), target.item(), false));
            }
        }
        for (int i = 0; i < cancel.size(); i++) cancelJob(cancel.get(i), reasons.get(i));
    }

    /** Whether this job still has the guard a remedy for {@code item} would satisfy. */
    private static boolean stillNeeds(Job job, String item, GuardFacts facts) {
        for (var guard : job.guards()) {
            if (guard instanceof dev.friendai.execution.queue.Guard.HasItem has && item.equals(has.item()))
                return has.evaluate(facts) == dev.friendai.execution.queue.Guard.Truth.FALSE;
            if (guard instanceof dev.friendai.execution.queue.Guard.ToolAtLeast tool
                    && item.equals(REPAIR_TOOLS.itemFor(tool.block()).orElse(null)))
                return tool.evaluate(facts) == dev.friendai.execution.queue.Guard.Truth.FALSE;
        }
        return false;
    }

    void startDefault() {
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
                                "follow",
                                null,
                                null,
                                false,
                                true,
                                false,
                                0,
                                0,
                                0,
                                false,
                                settings,
                                baseContext(),
                                LogContext.NONE,
                                "autonomy"));
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
        return queueDriver.isAwaitingDecision();
    }

    public long persistenceActiveJobId() {
        // A task waiting for its owner to log in is still the active work of its job: a save must keep the
        // document consistent (one RUNNING job with its active id) or the whole queue would be discarded.
        if (pendingRestoredTask != null && pendingRestoredJobId > 0) return pendingRestoredJobId;
        return activeTask() != null && activeTask().jobId() > 0
                ? activeTask().jobId()
                : frozenTask() == null ? 0 : frozenTask().jobId();
    }

    /**
     * The active ticks the activation named by {@link #persistenceTaskState()} has already spent. A save is a position
     * in the task's budget, so its remaining time depends on this number rather than on a fresh activation clock.
     */
    public int persistenceActiveElapsedTicks() {
        if (pendingRestoredTask != null) return pendingRestoredElapsedTicks;
        return activeTasks.elapsedActiveTicks();
    }

    /** The timeout the activation named by {@link #persistenceTaskState()} runs under, so a reload keeps its contract. */
    public int persistenceActiveTimeoutSec() {
        if (pendingRestoredTask != null) return pendingRestoredTimeoutSec;
        return activeTasks.activeTimeoutSec();
    }

    public Optional<TaskState> persistenceTaskState() {
        if (pendingRestoredTask != null) return persistenceState(pendingRestoredTask, "pending restored task");
        ActiveTask active = activeTask() != null && activeTask().jobId() > 0
                ? activeTask()
                : frozenTask() != null ? frozenTask() : activeTask();
        if (active == null || active.defaultActivity() || active.task().type().equals("follow"))
            return Optional.empty();
        return persistenceState(active.task(), "task " + active.toolName());
    }

    private Optional<TaskState> persistenceState(Task task, String label) {
        String data;
        try {
            data = task.saveData();
        } catch (RuntimeException error) {
            LOGGER.warn("{} failed to save its checkpoint", label, error);
            return Optional.empty();
        }
        if (data == null) return Optional.empty();
        try {
            return Optional.of(new TaskState(task.type(), data));
        } catch (IllegalArgumentException oversized) {
            LOGGER.warn("{} checkpoint exceeds the save limit", label);
            return Optional.empty();
        }
    }
    /** Installs validated state; decoding, authorization and recovery policy belong to TaskPersistenceCoordinator. */
    public void installRestoredState(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask) {
        installRestoredState(decoded, restoredFactories, restoredPlanFactories, restoredTask, Map.of(), 0, 0);
    }

    /** The same install with the progress saved for jobs that had failed and may run again. */
    public void installRestoredState(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask,
            Map<Long, RetryCheckpoints.Entry> retryProgress) {
        installRestoredState(decoded, restoredFactories, restoredPlanFactories, restoredTask, retryProgress, 0, 0);
    }

    public void installRestoredState(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask,
            Map<Long, RetryCheckpoints.Entry> retryProgress,
            int elapsedActiveTicks) {
        installRestoredState(
                decoded, restoredFactories, restoredPlanFactories, restoredTask, retryProgress, elapsedActiveTicks, 0);
    }

    /** The same install with the active budget and timeout the restored activation keeps from before the save. */
    public void installRestoredState(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask,
            Map<Long, RetryCheckpoints.Entry> retryProgress,
            int elapsedActiveTicks,
            int timeoutSec) {
        inTransition(() -> installRestoredStateActive(
                decoded,
                restoredFactories,
                restoredPlanFactories,
                restoredTask,
                retryProgress,
                elapsedActiveTicks,
                timeoutSec));
    }

    private void installRestoredStateActive(
            QueueSave.Decoded decoded,
            Map<Long, StepFactory> restoredFactories,
            Map<Long, Map<String, PlanStepFactory>> restoredPlanFactories,
            Task restoredTask,
            Map<Long, RetryCheckpoints.Entry> retryProgress,
            int elapsedActiveTicks,
            int timeoutSec) {
        syncSettings();
        retryCheckpoints.restore(retryProgress);
        pendingRestoredTask = null;
        pendingRestoredJobId = 0;
        pendingRestoredElapsedTicks = 0;
        pendingRestoredTimeoutSec = 0;
        queueDriver.restore(decoded, restoredFactories, restoredPlanFactories);
        activeTasks.reset();
        Job activeJob = decoded.activeJobId() == 0
                ? null
                : queueDriver.queue().job(decoded.activeJobId()).orElse(null);
        if (restoredTask != null && activeJob != null && activeJob.state() == JobState.RUNNING) {
            if (suspended) {
                pendingRestoredTask = restoredTask;
                pendingRestoredJobId = activeJob.id();
                pendingRestoredElapsedTicks = elapsedActiveTicks;
                pendingRestoredTimeoutSec = timeoutSec;
            } else {
                startRestoredTask(restoredTask, activeJob, elapsedActiveTicks, timeoutSec);
            }
        } else if (restoredTask != null && activeJob == null) {
            if (suspended) {
                pendingRestoredTask = restoredTask;
                pendingRestoredJobId = 0;
                pendingRestoredElapsedTicks = elapsedActiveTicks;
                pendingRestoredTimeoutSec = timeoutSec;
            } else {
                restore(restoredTask, elapsedActiveTicks, timeoutSec);
            }
        }
        for (Job job : List.copyOf(queueDriver.queue().jobs()))
            if (job.state() == JobState.RUNNING
                    && job.id() != pendingRestoredJobId
                    && (activeTask() == null || activeTask().jobId() != job.id())) queueDriver.requeue(job.id());
        queueDriver.rebuildAwaitingDecisionState();
    }

    private void startRestoredTask(Task restoredTask, Job activeJob) {
        startRestoredTask(restoredTask, activeJob, 0, 0);
    }

    private void startRestoredTask(Task restoredTask, Job activeJob, int elapsedActiveTicks, int timeoutSec) {
        // A save names the principal, not the access: a grant withdrawn while the checkpoint waited must not resume.
        if (!stillAuthorized(activeJob)) {
            mutations.cancelJob(activeJob.id(), REVOKED);
            return;
        }
        CreativeActionContext actionContext = actionContext(activeJob.principal(), activeJob.creativeSensitive());
        try {
            ServerSettings settings = settingsSupplier.get();
            activeTasks.start(
                    restoredTask,
                    new TaskStartContext(
                            activeJob.tool(),
                            callerFor(activeJob.principal()),
                            actionContext,
                            true,
                            false,
                            false,
                            (int) activeJob.id(),
                            activeJob.id(),
                            activeJob.planId(),
                            activeJob.creativeSensitive(),
                            settings,
                            new TaskContext(companion, (ServerLevel) companion.level(), this, actionContext, settings),
                            LogContext.NONE,
                            "restore"),
                    elapsedActiveTicks,
                    timeoutSec);
        } catch (RuntimeException e) {
            queueDriver.fail(activeJob.id(), activeTasks.crashSummary(e));
        }
    }

    TaskContext context() {
        ActiveTask active = activeTask() != null ? activeTask() : frozenTask();
        ServerSettings settings = active == null ? settingsSupplier.get() : active.settings();
        CreativeActionContext actionContext = active == null ? null : active.creativeActionContext();
        return context(settings, actionContext);
    }

    TaskContext context(ActiveTask active) {
        return context(active.settings(), active.creativeActionContext(), active.logContext());
    }

    private TaskContext baseContext() {
        return context(settingsSupplier.get(), null);
    }

    ActiveTask activeTask() {
        return activeTasks.currentActive();
    }

    /** The task this runner is executing, for tests and diagnostics that inspect its checkpoint. */
    public Task persistenceTask() {
        ActiveTask active = activeTask();
        return active == null ? null : active.task();
    }

    ActiveTask frozenTask() {
        return activeTasks.frozenActive();
    }

    void stopFrozen(String reason) {
        ActiveTask frozen = frozenTask();
        if (frozen != null) activeTasks.stopFrozen(new StopRequest(reason), context(frozen));
    }

    void emit(java.util.function.Consumer<TaskEventSink> event) {
        events.emit(event);
    }

    <T> T inTransition(java.util.function.Supplier<T> action) {
        return events.inTransition(action);
    }

    void inTransition(Runnable action) {
        events.inTransition(action);
    }

    private TaskContext context(ServerSettings settings, CreativeActionContext actionContext) {
        return context(settings, actionContext, LogContext.NONE);
    }

    private TaskContext context(ServerSettings settings, CreativeActionContext actionContext, LogContext logContext) {
        return new TaskContext(
                companion,
                (ServerLevel) companion.level(),
                this,
                actionContext,
                settings,
                logContext,
                structureBuilder);
    }

    private CreativeActionContext actionContext(Caller caller, boolean creativeSensitive) {
        return caller == null || caller.uuid() == null || !creativeSensitive
                ? null
                : new CreativeActionContext(caller.uuid(), companion.creativeModeRevision());
    }

    CreativeActionContext actionContext(java.util.UUID principal, boolean creativeSensitive) {
        return principal == null || !creativeSensitive
                ? null
                : new CreativeActionContext(principal, companion.creativeModeRevision());
    }
    /** Reconstructs only the authority that the saved principal proves; unknown actors stay guests. */
    private Caller callerFor(java.util.UUID principal) {
        if (principal == null) return null;
        AccessLevel access = principal.equals(companion.ownerUuid()) ? AccessLevel.OWNER : AccessLevel.GUEST;
        return new Caller(principal, "", access);
    }

    void requireCreativeAuthorization(java.util.UUID principal, boolean creativeSensitive) {
        if (!companion.creativeMode() || !creativeSensitive) return;
        CreativeActionContext context = actionContext(principal, true);
        if (!CreativeModeService.isActionAuthorized(companion, context, CreativeAction.WORLD_CHANGING_TASK)) {
            throw new IllegalArgumentException(
                    "creative mode requires its online operator owner for world-changing tasks");
        }
    }
}
