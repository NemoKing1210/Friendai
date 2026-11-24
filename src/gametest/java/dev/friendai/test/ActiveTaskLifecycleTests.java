package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.TaskDisposition;
import dev.friendai.execution.TaskWorkResult;
import dev.friendai.execution.log.TaskLogCorrelation;
import dev.friendai.execution.log.TaskLogEvent;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.HoldKey;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.acquisition.ObtainStep;
import dev.friendai.features.acquisition.minecraft.ObtainSubtaskFactory;
import dev.friendai.features.acquisition.minecraft.task.BatchObtainTask;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.crafting.CraftPlanner;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.foundation.diagnostics.DiagnosticClock;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.infrastructure.persistence.knowledge.KnowledgeStore;
import dev.friendai.server.execution.DiagnosticClockTestAccess;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ActiveTaskLifecycleTests {
    private static final class Events implements TaskEventSink {
        final List<String> log = new ArrayList<>();
        final List<TaskLogEvent> diagnostics = new ArrayList<>();
        final List<TaskDisposition> finishedDispositions = new ArrayList<>();
        final List<TaskDisposition> jobDispositions = new ArrayList<>();
        final List<String> finishedSummaries = new ArrayList<>();

        @Override
        public void onDiagnostic(TaskLogEvent event) {
            diagnostics.add(event);
        }

        List<TaskLogEvent> named(String name) {
            return diagnostics.stream()
                    .filter(event -> event.event().equals(name))
                    .toList();
        }

        List<TaskLogEvent> states() {
            return diagnostics.stream()
                    .filter(event -> event.event().equals(LogEvents.TASK_PROGRESS)
                            || event.event().equals(LogEvents.TASK_PHASE))
                    .toList();
        }

        Runnable onCancel = () -> {};

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            log.add("finished:" + toolName + ":" + status);
            finishedSummaries.add(summary);
        }

        @Override
        public void onFinished(
                int id,
                long planId,
                String toolName,
                TaskDisposition disposition,
                String summary,
                java.util.Optional<dev.friendai.execution.TaskWorkResult> workResult,
                long execution) {
            finishedDispositions.add(disposition);
            onFinished(
                    id,
                    toolName,
                    disposition == TaskDisposition.SUCCESS ? TaskStatus.SUCCESS : TaskStatus.FAILED,
                    summary);
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            log.add("cancelled:" + toolName + ":" + reason);
            onCancel.run();
        }

        @Override
        public void onJobFinished(
                long jobId, long planId, String toolName, TaskDisposition disposition, String summary, long execution) {
            jobDispositions.add(disposition);
        }
    }

    /** What the runner published when a queued step never became a task. */
    private static final class ActivationOutcomes implements TaskEventSink {
        record Row(long jobId, long planId, String tool, TaskDisposition disposition, String summary, long execution) {}

        final List<Row> jobs = new ArrayList<>();
        final List<Row> finished = new ArrayList<>();
        long blockedJobId;
        String blockedReason = "";
        long finishedPlanId;
        TaskDisposition planDisposition;
        String planSummary = "";

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {}

        @Override
        public void onFinished(
                int id,
                long planId,
                String toolName,
                TaskDisposition disposition,
                String summary,
                java.util.Optional<TaskWorkResult> workResult,
                long execution) {
            finished.add(new Row(id, planId, toolName, disposition, summary, execution));
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onJobFinished(
                long jobId,
                long planId,
                String toolName,
                TaskDisposition disposition,
                String summary,
                long execution,
                java.util.Optional<TaskWorkResult> workResult) {
            jobs.add(new Row(jobId, planId, toolName, disposition, summary, execution));
        }

        @Override
        public void onPlanBlocked(
                long planId,
                String goal,
                long jobId,
                String toolName,
                String reason,
                int remaining,
                boolean remainingExact) {
            blockedJobId = jobId;
            blockedReason = reason;
        }

        @Override
        public void onPlanFinished(
                long planId,
                String goal,
                TaskDisposition disposition,
                String summary,
                java.util.Optional<TaskWorkResult> workResult) {
            finishedPlanId = planId;
            planDisposition = disposition;
            planSummary = summary;
        }
    }

    /** What the queue said at a plan boundary, with the receipt each boundary was obliged to carry. */
    private static final class PlanBoundaries implements TaskEventSink {
        long blockedPlanId;
        long blockedJobId;
        String blockedTool;
        java.util.Optional<TaskWorkResult> blockedReceipt = java.util.Optional.empty();
        TaskDisposition finishedDisposition;
        java.util.Optional<TaskWorkResult> finishedReceipt = java.util.Optional.empty();

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {}

        @Override
        public void onCancelled(int id, String toolName, String reason) {}

        @Override
        public void onPlanBlocked(
                long planId,
                String goal,
                long jobId,
                String toolName,
                String reason,
                int remaining,
                boolean remainingExact,
                java.util.Optional<TaskWorkResult> workResult) {
            blockedPlanId = planId;
            blockedJobId = jobId;
            blockedTool = toolName;
            blockedReceipt = workResult;
        }

        @Override
        public void onPlanFinished(
                long planId,
                String goal,
                TaskDisposition disposition,
                String summary,
                java.util.Optional<TaskWorkResult> workResult) {
            finishedDisposition = disposition;
            finishedReceipt = workResult;
        }
    }

    /**
     * A gather-and-deliver step that measured a partial hand-over before it stopped: exactly the shape that must not
     * leave the queue as a bare summary.
     */
    private static final class PartialGiveTask implements Task {
        @Override
        public String type() {
            return "give_items";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.FAILED;
        }

        @Override
        public String resultSummary() {
            return "owner is too far away";
        }

        @Override
        public java.util.Optional<TaskWorkResult> workResult() {
            return java.util.Optional.of(new TaskWorkResult(
                    List.of(new TaskWorkResult.Line("minecraft:wheat", 8, 3, TaskWorkResult.Evidence.HANDED_OVER)),
                    "partial",
                    "only gave 3 of 8 minecraft:wheat",
                    "retry the remaining share"));
        }

        @Override
        public String describe() {
            return "handing over wheat";
        }

        @Override
        public void stop(TaskContext context) {}
    }

    private static final class ProgressingTask implements Task {
        private long stamp;

        @Override
        public String type() {
            return "progressing_timeout";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            stamp++;
            return TaskStatus.RUNNING;
        }

        @Override
        public long progressStamp() {
            return stamp;
        }

        @Override
        public boolean hasTimeout() {
            return true;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "making progress until its deadline";
        }

        @Override
        public String resultSummary() {
            return "timeout fixture";
        }

        @Override
        public java.util.Optional<TaskWorkResult> workResult() {
            return java.util.Optional.of(new TaskWorkResult(
                    List.of(new TaskWorkResult.Line("minecraft:iron_ore", 2, 1, TaskWorkResult.Evidence.PICKED_UP)),
                    "partial",
                    "one item remains",
                    "continue pickup"));
        }
    }

    private static final class ReplacingTask implements Task {
        int stops;

        @Override
        public String type() {
            return "replace_during_tick";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            context.execution().start(new StayTask(), "stay");
            return TaskStatus.SUCCESS;
        }

        @Override
        public void stop(TaskContext context) {
            stops++;
        }

        @Override
        public String describe() {
            return "replacing task";
        }

        @Override
        public String resultSummary() {
            return "finished";
        }
    }

    private static final class NoopTask implements Task {
        @Override
        public String type() {
            return "noop_repair";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            return TaskStatus.SUCCESS;
        }

        @Override
        public String resultSummary() {
            return "repaired";
        }

        @Override
        public String describe() {
            return "repairing";
        }

        @Override
        public void stop(TaskContext context) {}
    }

    private static final class MutableClock implements DiagnosticClock {
        long millis = 10000;

        @Override
        public long wallMillis() {
            return millis;
        }

        @Override
        public long nanoTime() {
            return millis * 1000000L;
        }
    }

    private static final class DiagnosticTask implements Task {
        long stamp = 7;
        int waiting = -1;
        int ticks;
        String detail = "extract 4 67 -10 waiting for drop";
        Runnable afterTick = () -> {};

        @Override
        public String type() {
            return "diagnostic_fixture";
        }

        @Override
        public void start(TaskContext context) {}

        @Override
        public TaskStatus tick(TaskContext context) {
            ticks++;
            afterTick.run();
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext context) {}

        @Override
        public String describe() {
            return "diagnostic fixture";
        }

        @Override
        public String debugStatus() {
            return detail;
        }

        @Override
        public String resultSummary() {
            return "diagnostic fixture finished";
        }

        @Override
        public long progressStamp() {
            return stamp;
        }

        @Override
        public int waitingTicks() {
            return waiting;
        }

        @Override
        public int waitingLimitTicks() {
            return 600;
        }
    }

    @GameTest
    public void staticStampReportsInitialAndTwoNoProgressHeartbeats(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, new MutableClock());
        Events events = new Events();
        runner.setEventSink(events);
        runner.start(new DiagnosticTask(), "static_diagnostic");
        try {
            for (int i = 0; i < 401; i++) runner.tick();
            h.assertValueEqual(events.states().size(), 3, "initial, +200 and +400 active ticks");
            h.assertValueEqual(
                    events.states().stream().map(e -> e.facts().get("reason")).toList(),
                    List.of("running", "sample", "sample"),
                    "heartbeat reasons");
            h.assertTrue(events.named(LogEvents.TASK_SNAPSHOT).isEmpty(), "periodic work must not emit snapshots");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest
    public void movingStampReportsAtOneHundredActiveTicks(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, new MutableClock());
        Events events = new Events();
        runner.setEventSink(events);
        DiagnosticTask task = new DiagnosticTask();
        task.afterTick = () -> task.stamp++;
        runner.start(task, "moving_diagnostic");
        try {
            for (int i = 0; i < 100; i++) runner.tick();
            h.assertValueEqual(events.states().size(), 1, "no heartbeat before +100");
            runner.tick();
            h.assertValueEqual(events.states().size(), 2, "heartbeat at +100");
            for (int i = 0; i < 100; i++) runner.tick();
            h.assertValueEqual(events.states().size(), 3, "heartbeat at +200");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest
    public void waitingReportsEntryPeriodicSampleAndImmediateExit(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, new MutableClock());
        Events events = new Events();
        runner.setEventSink(events);
        DiagnosticTask task = new DiagnosticTask();
        task.waiting = 0;
        runner.start(task, "waiting_diagnostic");
        try {
            for (int i = 0; i < 201; i++) {
                task.waiting = i;
                runner.tick();
            }
            h.assertValueEqual(events.states().size(), 2, "waiting entry and +200 sample");
            TaskLogEvent entry = events.states().getFirst();
            TaskLogEvent sample = events.states().getLast();
            h.assertValueEqual(entry.event(), LogEvents.TASK_PHASE, "waiting transition event");
            h.assertValueEqual(sample.event(), LogEvents.TASK_PROGRESS, "waiting heartbeat event");
            h.assertValueEqual(sample.facts().get("phase"), "waiting", "waiting phase");
            h.assertValueEqual(sample.facts().get("reason"), "waiting", "waiting reason");
            h.assertValueEqual(sample.facts().get("waitingTicks"), "200", "waiting duration");
            h.assertValueEqual(sample.facts().get("waitingLimitTicks"), "600", "waiting limit");
            h.assertValueEqual(sample.facts().get("stamp"), "7", "progress stamp");
            h.assertValueEqual(sample.facts().get("detail"), task.detail, "complete task detail");
            task.waiting = -1;
            runner.tick();
            h.assertValueEqual(events.states().size(), 3, "leaving waiting emits immediately");
            h.assertValueEqual(events.states().getLast().facts().get("phase"), "progress", "running phase");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest
    public void clockGapCapturesTheInterruptedExecutionBeforeItsNextTick(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        MutableClock clock = new MutableClock();
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, clock);
        Events events = new Events();
        runner.setEventSink(events);
        DiagnosticTask task = new DiagnosticTask();
        QueuedStep step = new QueuedStep(
                "gap_diagnostic", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long plan = runner.enqueuePlan("gap identity", List.of(step), List.of(context -> task), PlanOptions.DEFAULT);
        long job = runner.queue().jobsOfPlan(plan).getFirst().id();
        try {
            runner.tick();
            TaskLogEvent start = events.named(LogEvents.TASK_START).getFirst();
            String interrupted = task.detail;
            task.afterTick = () -> task.detail = "resumed at a different cell";
            clock.millis += 45452;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 1, "one observed gap");
            TaskLogEvent gap = events.named(LogEvents.CLOCK_GAP).getFirst();
            h.assertValueEqual(gap.tool(), "gap_diagnostic", "interrupted tool");
            h.assertValueEqual(gap.context(), start.context(), "complete activation identity");
            h.assertValueEqual(gap.context().plan(), plan, "plan identity");
            h.assertValueEqual(gap.context().job(), job, "job identity");
            h.assertTrue(gap.context().task() > 0, "task identity");
            h.assertValueEqual(
                    gap.facts().get("task"), Long.toString(start.context().task()), "task fact");
            h.assertValueEqual(gap.facts().get("plan"), Long.toString(plan), "plan fact");
            h.assertValueEqual(gap.facts().get("job"), Long.toString(job), "job fact");
            h.assertValueEqual(gap.facts().get("phase"), interrupted, "phase before resuming");
            h.assertValueEqual(gap.facts().get("reason"), "unknown", "gap cause stays unknown");
            h.assertValueEqual(gap.facts().get("gapMs"), "45452", "wall gap duration");
            h.assertValueEqual(gap.durationMs(), 45452L, "event duration");
            TaskLogCorrelation correlation = new TaskLogCorrelation();
            correlation.recordStart(start, 123);
            h.assertValueEqual(correlation.parentSeq(gap), 123L, "gap descends from its task start");
            h.assertValueEqual(task.detail, "resumed at a different cell", "task resumed after capture");
            h.assertValueEqual(task.ticks, 2, "wall time does not create extra delivered ticks");
            h.assertValueEqual(events.states().size(), 1, "wall gap does not advance heartbeat cadence");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest
    public void clockGapPrefersCurrentAndFallsBackToFrozenWork(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        MutableClock clock = new MutableClock();
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, clock);
        Events events = new Events();
        runner.setEventSink(events);
        DiagnosticTask frozen = new DiagnosticTask();
        runner.start(frozen, "frozen_diagnostic");
        runner.tick();
        DiagnosticClockTestAccess.freezeCurrent(runner);
        frozen.detail = " ";
        runner.setPaused(true);
        try {
            clock.millis += 2000;
            runner.tick();
            h.assertValueEqual(
                    events.named(LogEvents.CLOCK_GAP).getFirst().tool(), "frozen_diagnostic", "frozen fallback");
            h.assertValueEqual(
                    events.named(LogEvents.CLOCK_GAP).getFirst().facts().get("phase"),
                    "diagnostic fixture",
                    "blank detail falls back to description");
            clock.millis += 50;
            runner.tick();
            runner.interrupt(new DiagnosticTask(), "urgent_diagnostic");
            clock.millis += 2000;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 2, "two separate observed gaps");
            h.assertValueEqual(
                    events.named(LogEvents.CLOCK_GAP).getLast().tool(), "urgent_diagnostic", "current beats frozen");
            h.assertValueEqual(frozen.ticks, 1, "held/frozen work never resumed");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest
    public void idleClockGapHonorsBoundaryRollbackAndConsecutiveSuppression(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        MutableClock clock = new MutableClock();
        ExecutionCoordinator runner = DiagnosticClockTestAccess.create(companion, clock);
        Events events = new Events();
        runner.setEventSink(events);
        try {
            runner.tick();
            clock.millis += 1000;
            runner.tick();
            h.assertTrue(events.named(LogEvents.CLOCK_GAP).isEmpty(), "1000ms is not a gap");
            clock.millis -= 300;
            runner.tick();
            h.assertTrue(events.named(LogEvents.CLOCK_GAP).isEmpty(), "clock rollback is not a gap");
            clock.millis += 1001;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 1, "1001ms exceeds threshold");
            TaskLogEvent gap = events.named(LogEvents.CLOCK_GAP).getFirst();
            h.assertValueEqual(gap.context(), LogContext.NONE, "idle context");
            h.assertValueEqual(gap.tool(), "", "idle tool");
            h.assertValueEqual(gap.parent(), TaskLogEvent.Parent.INVOCATION, "idle parent");
            h.assertTrue(!gap.facts().containsKey("phase"), "idle has no invented phase");
            clock.millis += 2000;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 1, "consecutive large gaps are suppressed");
            clock.millis += 1000;
            runner.tick();
            clock.millis += 2000;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 2, "normal tick resets suppression");
            clock.millis -= 50;
            runner.tick();
            clock.millis += 2000;
            runner.tick();
            h.assertValueEqual(events.named(LogEvents.CLOCK_GAP).size(), 3, "rollback also resets suppression");
        } finally {
            runner.stopAll("test cleanup");
        }
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aPrerequisiteFinishesBeforeTheDependentStep(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        List<String> order = new ArrayList<>();
        runner.setEventSink(new TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {
                order.add(toolName);
            }

            @Override
            public void onCancelled(int id, String toolName, String reason) {}
        });
        QueuedStep dependent = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "dependency test",
                List.of(dependent),
                List.of(context -> new WaitTask(1)),
                PlanOptions.DEFAULT,
                id -> runner.queue().setHold(HoldKey.plan(id), QueueHold.pause("test setup")));
        long jobId = runner.queue().jobsOfPlan(planId).getFirst().id();
        var inserted = runner.queue().insertPrerequisite(jobId, dependent);
        h.assertTrue(inserted.ok(), inserted.text());
        long remedy = runner.queue().jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("wait"))
                .mapToLong(job -> job.id())
                .max()
                .orElseThrow();
        runner.registerFactory(remedy, context -> new WaitTask(1));
        runner.queue().release(HoldKey.plan(planId));
        h.succeedWhen(() -> {
            runner.tick();
            if (order.size() < 2) return;
            h.assertValueEqual(order, List.of("wait", "wait"), "the prerequisite finishes first: " + order);
            h.assertValueEqual(
                    runner.queue().job(remedy).orElseThrow().state(),
                    dev.friendai.execution.queue.JobState.DONE,
                    "remedy done");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aDeferredPlanDoesNotWakeBeforeItsTick(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        QueuedStep wait = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "defer test",
                List.of(wait),
                List.of(context -> new WaitTask(20)),
                PlanOptions.DEFAULT,
                id -> runner.queue()
                        .setHold(
                                HoldKey.plan(id),
                                QueueHold.defer("later", runner.queue().clock() + 5, null)));
        int[] tick = {0};
        h.succeedWhen(() -> {
            tick[0]++;
            if (tick[0] < 5) {
                runner.tick();
                h.assertTrue(runner.current().isEmpty(), "a deferred plan woke early at tick " + tick[0]);
                return;
            }
            runner.tick();
            h.assertTrue(runner.current().isPresent(), "the deferred plan starts when its tick arrives");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aBlockedQueuedStepReportsWhatItMeasured(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        PlanBoundaries boundaries = new PlanBoundaries();
        runner.setEventSink(boundaries);
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "store the wheat", List.of(give), List.of(context -> new PartialGiveTask()), PlanOptions.DEFAULT);
        long jobId = runner.queue().jobsOfPlan(planId).getFirst().id();
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(boundaries.blockedReceipt.isPresent(), "no plan boundary receipt yet");
            h.assertValueEqual(boundaries.blockedPlanId, planId, "the blocker names its plan");
            h.assertValueEqual(boundaries.blockedJobId, jobId, "the receipt stays next to the failed step");
            h.assertValueEqual(boundaries.blockedTool, "give_items", "the blocker names the step's tool");
            h.assertTrue(
                    boundaries.blockedReceipt.get().compact().contains("minecraft:wheat handed_over=3/8 remaining=5"),
                    "the settlement keeps what the step verified: "
                            + boundaries.blockedReceipt.get().compact());
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aTerminalQueuedPlanCarriesWhatItsLastStepMeasured(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        PlanBoundaries boundaries = new PlanBoundaries();
        runner.setEventSink(boundaries);
        QueuedStep give = new QueuedStep(
                "give_items",
                new JsonObject(),
                null,
                List.of(),
                GuardAction.WAIT,
                FailurePolicy.STOP_PLAN,
                1,
                List.of());
        runner.enqueuePlan(
                "store the wheat", List.of(give), List.of(context -> new PartialGiveTask()), PlanOptions.DEFAULT);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(boundaries.finishedReceipt.isPresent(), "no terminal plan receipt yet");
            h.assertTrue(
                    boundaries.finishedReceipt.get().compact().contains("minecraft:wheat handed_over=3/8 remaining=5"),
                    "a terminal plan keeps the last step's verified work: "
                            + boundaries.finishedReceipt.get().compact());
            h.assertValueEqual(boundaries.finishedDisposition, TaskDisposition.FAILED, "the plan ends failed");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aQueuedStepRefusedAtActivationStillBecomesAnOutcome(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        ActivationOutcomes outcomes = new ActivationOutcomes();
        runner.setEventSink(outcomes);
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "store the wheat",
                List.of(give),
                List.of(context -> {
                    throw new IllegalArgumentException("owner is not in this dimension");
                }),
                PlanOptions.DEFAULT);
        long jobId = runner.queue().jobsOfPlan(planId).getFirst().id();
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(outcomes.jobs.size(), 1, "one refused activation: " + outcomes.jobs);
            ActivationOutcomes.Row row = outcomes.jobs.getFirst();
            h.assertValueEqual(row.jobId(), jobId, "the row keeps the job identity");
            h.assertValueEqual(row.planId(), planId, "the row keeps the plan identity");
            h.assertValueEqual(row.tool(), "give_items", "the row names the refused tool");
            h.assertValueEqual(row.disposition(), TaskDisposition.FAILED, "a refusal is a failure");
            h.assertValueEqual(row.summary(), "owner is not in this dimension", "the factory's own reason survives");
            h.assertValueEqual(row.execution(), 0L, "no task instance ran");
            h.assertTrue(outcomes.finished.isEmpty(), "the plan boundary carries it: " + outcomes.finished);
            h.assertValueEqual(outcomes.blockedJobId, jobId, "the blocker names the same job");
            h.assertValueEqual(
                    outcomes.blockedReason, "owner is not in this dimension", "the plan says why the step stopped");
            h.assertValueEqual(
                    runner.queue().job(jobId).orElseThrow().state(),
                    JobState.FAILED,
                    "the refused job stays in the plan for the panel");
            h.assertTrue(runner.current().isEmpty(), "no task was started");
            h.assertValueEqual(companion.inventory().isEmpty(), true, "the refusal changed nothing");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aDirectStepRefusedAtActivationSettlesOnceAndLeavesTheQueue(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        ActivationOutcomes outcomes = new ActivationOutcomes();
        runner.setEventSink(outcomes);
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long jobId = runner.enqueue(give, context -> {
            throw new IllegalArgumentException("no owner is set");
        });
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(outcomes.jobs.size(), 1, "one queue row: " + outcomes.jobs);
            h.assertValueEqual(outcomes.jobs.getFirst().jobId(), jobId, "the row keeps the job identity");
            h.assertValueEqual(outcomes.jobs.getFirst().planId(), 0L, "a direct job has no plan");
            h.assertValueEqual(outcomes.jobs.getFirst().summary(), "no owner is set", "the reason survives");
            h.assertValueEqual(outcomes.finished.size(), 1, "the direct job reaches the brain once");
            h.assertValueEqual(outcomes.finished.getFirst().disposition(), TaskDisposition.FAILED, "failed");
            h.assertTrue(runner.queue().job(jobId).isEmpty(), "the refused direct job leaves the queue");
            h.assertTrue(outcomes.blockedJobId == 0L, "no plan boundary for a direct job");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 60)
    public void aRevokedGrantSettlesTheStepAsCancelled(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        ActivationOutcomes outcomes = new ActivationOutcomes();
        runner.setEventSink(outcomes);
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "store the wheat",
                List.of(give),
                List.of(new dev.friendai.task.plan.StepFactory() {
                    @Override
                    public Task create(TaskContext context) {
                        throw new AssertionError("a revoked step must never be built");
                    }

                    @Override
                    public boolean authorized(TaskContext context) {
                        return false;
                    }
                }),
                PlanOptions.DEFAULT);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(outcomes.jobs.size(), 1, "one row for the revoked step: " + outcomes.jobs);
            h.assertValueEqual(
                    outcomes.jobs.getFirst().disposition(), TaskDisposition.CANCELLED, "nothing was attempted");
            h.assertTrue(
                    outcomes.jobs.getFirst().summary().contains("was revoked before the action started"),
                    "the row says the grant is gone: "
                            + outcomes.jobs.getFirst().summary());
            h.assertValueEqual(outcomes.finishedPlanId, planId, "the plan still ends");
            h.assertValueEqual(outcomes.planDisposition, TaskDisposition.FAILED, "the plan ends failed");
            h.assertTrue(outcomes.planSummary.contains("revoked"), "the plan says why: " + outcomes.planSummary);
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 60)
    public void aRestoredJobWithoutAFactorySettlesOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity first = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator before = new ExecutionCoordinator(first, () -> 300, () -> false);
        before.start(new WaitTask(40), "hold");
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long jobId = before.enqueue(give, context -> new WaitTask(1));
        QueueSave.Decoded saved = new QueueSave.Decoded(before.queue(), 0);
        before.stop("simulated restart");
        CompanionEntity reloaded = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        ExecutionCoordinator runner = new ExecutionCoordinator(reloaded, () -> 300, () -> false);
        ActivationOutcomes outcomes = new ActivationOutcomes();
        runner.setEventSink(outcomes);
        runner.installRestoredState(saved, java.util.Map.of(), java.util.Map.of(), null);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(outcomes.jobs.size(), 1, "the restored job settles once: " + outcomes.jobs);
            h.assertValueEqual(outcomes.jobs.getFirst().jobId(), jobId, "the same job identity");
            h.assertValueEqual(
                    outcomes.jobs.getFirst().summary(),
                    "task factory is unavailable for give_items",
                    "the row names the missing factory and the tool");
            h.assertValueEqual(outcomes.finished.size(), 1, "the brain hears about it once");
            h.assertTrue(runner.queue().job(jobId).isEmpty(), "the unusable job leaves the queue");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 80)
    public void activationRefusalsSettleInBoundedSteps(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        ActivationOutcomes outcomes = new ActivationOutcomes();
        runner.setEventSink(outcomes);
        // A running task keeps every enqueue from starting immediately, so the whole batch faces one scheduler step.
        runner.start(new WaitTask(2), "hold");
        QueuedStep give = new QueuedStep(
                "give_items", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        for (int i = 0; i < 12; i++) {
            runner.enqueue(give, context -> {
                throw new IllegalArgumentException("owner is away");
            });
        }
        h.assertValueEqual(outcomes.jobs.size(), 0, "the held task kept the batch from starting");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(!outcomes.jobs.isEmpty(), "no activation refusal settled yet");
            h.assertTrue(
                    outcomes.jobs.size() < 12,
                    "one scheduler step must not settle the whole queue: " + outcomes.jobs.size());
            for (int i = 0; i < 20 && outcomes.jobs.size() < 12; i++) runner.tick();
            h.assertValueEqual(outcomes.jobs.size(), 12, "the rest settle on later polls");
            runner.stop("test cleanup");
        });
    }

    @GameTest(maxTicks = 40)
    public void aToolGuardGetsOnePickaxePrerequisite(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setRepairFactory((principal, args) ->
                java.util.Optional.of(new ExecutionCoordinator.RepairPlan(context -> new NoopTask(), false)));
        QueuedStep blocked = new QueuedStep(
                "mine_blocks",
                new JsonObject(),
                null,
                List.of(new Guard.ToolAtLeast("minecraft:iron_ore")),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of());
        runner.enqueuePlan("mine iron", List.of(blocked), List.of(context -> new NoopTask()), PlanOptions.DEFAULT);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(
                    runner.idleRepairInserted(),
                    1,
                    "one pickaxe prerequisite; jobs=" + runner.queue().jobs());
        });
    }

    @GameTest(maxTicks = 60)
    public void aPlanHoldAndAJobHoldWithTheSameNumberDoNotReachEachOther(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        QueuedStep wait = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long[] ids = {0, 0};
        long planId = runner.enqueuePlan(
                "held plan",
                new PlanDefinition(List.of(new PlanNode.Action(wait))),
                PlanOptions.DEFAULT,
                java.util.Map.of("wait", (context, step) -> new WaitTask(20)),
                id -> {
                    ids[0] = id;
                    Job direct = runner.queue().addDirect(wait);
                    ids[1] = direct.id();
                    runner.registerFactory(direct.id(), context -> new WaitTask(20));
                    runner.queue().setHold(HoldKey.plan(id), QueueHold.pause("plan held"));
                    runner.queue().setHold(HoldKey.job(direct.id()), QueueHold.pause("job held"));
                });
        long directId = ids[1];
        h.assertValueEqual(directId, planId, "a plan and an unrelated direct job share the number");
        runner.tick();
        h.assertTrue(runner.current().isEmpty(), "both are held");

        runner.queue().release(HoldKey.job(directId));
        runner.wakeQueue();
        for (int i = 0; i < 5 && runner.current().isEmpty(); i++) runner.tick();
        h.assertTrue(!runner.current().isEmpty(), "releasing the job hold starts the job");
        h.assertValueEqual(
                runner.queue().active().map(Job::id).orElse(0L), directId, "the resumed job runs, not the held plan");
        h.assertTrue(runner.queue().hold(HoldKey.plan(planId)).isPresent(), "the plan hold survives the job resume");
        h.assertTrue(runner.queue().jobsOfPlan(planId).isEmpty(), "the held plan emitted no step");

        // Reload: each hold keeps its own scope, and resuming one leaves the other where it was.
        QueueSave.Decoded saved = new QueueSave.Decoded(
                runner.queue(), runner.queue().active().map(Job::id).orElse(0L));
        runner.stop("simulated restart");
        CompanionEntity reloaded = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        ExecutionCoordinator again = new ExecutionCoordinator(reloaded, () -> 300, () -> false);
        again.installRestoredState(
                saved,
                java.util.Map.of(directId, context -> new WaitTask(20)),
                java.util.Map.of(planId, java.util.Map.of("wait", (context, step) -> new WaitTask(20))),
                null);
        h.assertTrue(again.queue().hold(HoldKey.plan(planId)).isPresent(), "the plan hold survives the reload");
        h.assertTrue(again.queue().hold(HoldKey.job(directId)).isEmpty(), "the resumed job was not re-held");
        again.queue().release(HoldKey.plan(planId));
        again.wakeQueue();
        // The requeued direct job takes the body first and runs its twenty ticks; the plan emits once it is free.
        for (int i = 0; i < 30 && again.queue().jobsOfPlan(planId).isEmpty(); i++) again.tick();
        h.assertTrue(!again.queue().jobsOfPlan(planId).isEmpty(), "the resumed plan emits its step after reload");
        again.stop("test cleanup");
        runner.stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aPausedPlanWaitsForResume(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        QueuedStep wait = new QueuedStep(
                "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
        long planId = runner.enqueuePlan(
                "pause test",
                List.of(wait),
                List.of(context -> new WaitTask(20)),
                PlanOptions.DEFAULT,
                id -> runner.queue().setHold(HoldKey.plan(id), QueueHold.pause("owner")));
        runner.tick();
        h.assertTrue(runner.current().isEmpty(), "a paused plan must not start");
        runner.queue().release(HoldKey.plan(planId));
        runner.wakeQueue();
        runner.tick();
        h.assertTrue(runner.current().isPresent(), "a resumed plan starts");
        runner.stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aMissingItemGuardGetsOneIdlePrerequisite(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setRepairFactory((principal, args) ->
                java.util.Optional.of(new ExecutionCoordinator.RepairPlan(context -> new NoopTask(), false)));
        JsonObject args = new JsonObject();
        args.addProperty("block", "minecraft:oak_planks");
        QueuedStep blocked = new QueuedStep(
                "place_block",
                args,
                null,
                List.of(new Guard.HasItem("minecraft:oak_planks", 1)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of(),
                null,
                false);
        runner.enqueuePlan("place planks", List.of(blocked), List.of(context -> new NoopTask()), PlanOptions.DEFAULT);
        h.succeedWhen(() -> {
            runner.tick();
            h.assertValueEqual(
                    runner.idleRepairInserted(),
                    1,
                    "exactly one idle remedy; attempts=" + runner.idleRepairAttempts() + " jobs="
                            + runner.queue().jobs());
        });
    }

    @GameTest
    public void reentrantReplacementDoesNotCompleteOrEraseTheReplacedTask(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        ReplacingTask task = new ReplacingTask();
        runner.start(task, "replace_during_tick");

        runner.tick();

        h.assertValueEqual(task.stops, 1, "the replaced task must stop once");
        h.assertTrue(
                runner.current().map(Task::type).filter("stay"::equals).isPresent(),
                "the replacement must remain active");
        h.assertTrue(
                events.log.equals(List.of("cancelled:replace_during_tick:replaced by a new task")),
                "terminal events were " + events.log);
        h.assertTrue(events.states().isEmpty(), "the replaced activation must not emit state after replacement");
        runner.stop("test cleanup");
        h.succeed();
    }

    @GameTest
    public void cancellationListenerRunsAfterReplacementTransitionSettles(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        runner.start(new StayTask(), "original");
        boolean[] startedFromListener = {false};
        events.onCancel = () -> {
            if (!startedFromListener[0]) {
                startedFromListener[0] = true;
                runner.start(new StayTask(), "listener_task");
            }
        };

        runner.start(new StayTask(), "outer_task");

        h.assertTrue(
                runner.current().map(Task::type).filter("stay"::equals).isPresent()
                        && runner.currentToolName().equals("listener_task"),
                "a task started by the settled cancellation callback must remain active");
        h.assertTrue(events.log.size() == 2, "each replaced task must emit one cancellation: " + events.log);
        h.assertTrue(
                events.log.get(0).contains("original") && events.log.get(1).contains("outer_task"),
                "cancellations should follow settled transition order: " + events.log);
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void taskTimeoutHasItsOwnTerminalDisposition(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 1, () -> false);
        runner.setEventSink(events);
        runner.start(new ProgressingTask(), "timeout_fixture");

        for (int i = 0; i < 22; i++) runner.tick();

        h.assertValueEqual(events.finishedDispositions, List.of(TaskDisposition.TIMED_OUT), "typed timeout result");
        h.assertTrue(
                events.named(LogEvents.TASK_FINISH).size() == 1
                        && events.named(LogEvents.TASK_FINISH)
                                .getFirst()
                                .status()
                                .equals("timed_out"),
                "timeout must end with task.finish=timed_out: " + events.named(LogEvents.TASK_FINISH));
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void cancellationRetainsPartialWorkWithoutEmittingFailure(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        runner.start(new ProgressingTask(), "partial_pickup");
        runner.tick();
        runner.stop("owner cleared the queue");

        TaskLogEvent terminal = events.named(LogEvents.TASK_CANCEL).getFirst();
        h.assertValueEqual(terminal.facts().get("disposition"), "cancelled", "cancel terminal disposition");
        h.assertTrue(
                terminal.facts().getOrDefault("workResult", "").contains("picked_up=1/2 remaining=1"),
                "partial pickup stays attached to its cancelled execution: " + terminal.facts());
        h.assertTrue(events.finishedDispositions.isEmpty(), "cancellation must not emit a failed completion");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void holdAndResumeKeepTheSameExecutionIdentity(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        ProgressingTask task = new ProgressingTask();
        runner.start(task, "held_execution");
        runner.tick();
        TaskLogEvent started = events.named(LogEvents.TASK_START).getFirst();

        runner.setPaused(true);
        runner.tick();
        h.assertValueEqual(task.progressStamp(), 1L, "a held tick does not advance the task");
        runner.setPaused(false);
        runner.tick();
        h.assertValueEqual(task.progressStamp(), 2L, "the same task resumes after its hold");
        runner.stop("test cleanup");

        TaskLogEvent cancelled = events.named(LogEvents.TASK_CANCEL).getFirst();
        h.assertValueEqual(cancelled.context().execution(), started.context().execution(), "hold keeps execution id");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void deadlineSettlesAFrozenJobOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        runner.enqueuePlan(
                "short deadline",
                List.of(new QueuedStep(
                        "held_job", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of())),
                List.of(context -> new WaitTask(30)),
                new PlanOptions(0, FailurePolicy.ASK, 4, false));
        runner.tick();
        TaskLogEvent start = events.named(LogEvents.TASK_START).getFirst();
        runner.interrupt(new WaitTask(5), "urgent_job");
        for (int tick = 0; tick < 8; tick++) runner.tick();

        h.assertValueEqual(events.jobDispositions, List.of(TaskDisposition.TIMED_OUT), "one frozen job settlement");
        List<TaskLogEvent> terminal = events.diagnostics.stream()
                .filter(event -> event.terminal()
                        && event.context().execution() == start.context().execution())
                .toList();
        h.assertValueEqual(terminal.size(), 1, "one terminal event for the original execution");
        h.assertValueEqual(terminal.getFirst().status(), "timed_out", "deadline disposition");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void planLocalAndGlobalCancellationPublishEachQueuedJobOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events planEvents = new Events();
        ExecutionCoordinator planRunner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        planRunner.setEventSink(planEvents);
        long planId = planRunner.enqueuePlan(
                "cancel plan",
                List.of(
                        new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()),
                        new QueuedStep(
                                "stay", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of())),
                List.of(context -> new WaitTask(10), context -> new StayTask()),
                PlanOptions.DEFAULT,
                id -> planRunner.queue().setHold(HoldKey.plan(id), QueueHold.pause("test")));

        planRunner.cancelPlan(planId, "owner cancelled plan");

        h.assertValueEqual(
                planEvents.jobDispositions,
                List.of(TaskDisposition.CANCELLED, TaskDisposition.CANCELLED),
                "each plan job gets one cancellation row");
        h.assertTrue(planEvents.named(LogEvents.TASK_CANCEL).size() == 2, "each queued job gets one terminal log");
        h.assertTrue(planRunner.queue().plans().isEmpty(), "the cancelled plan should be archived");

        Events clearEvents = new Events();
        ExecutionCoordinator clearRunner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        clearRunner.setEventSink(clearEvents);
        clearRunner
                .queue()
                .addDirect(new QueuedStep(
                        "wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()));
        clearRunner
                .queue()
                .addPlan(
                        "global clear",
                        List.of(new QueuedStep(
                                "stay", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of())),
                        PlanOptions.DEFAULT);

        clearRunner.clearQueue("owner cleared all work");

        h.assertValueEqual(
                clearEvents.log.stream()
                        .filter(line -> line.startsWith("cancelled:"))
                        .count(),
                1L,
                "the queued direct job receives one cancellation");
        h.assertValueEqual(clearEvents.jobDispositions, List.of(TaskDisposition.CANCELLED), "one plan job row");
        h.assertTrue(clearEvents.named(LogEvents.TASK_CANCEL).size() == 2, "global clear logs each queued job once");
        h.succeed();
    }

    @GameTest(maxTicks = 40)
    public void restoredActivationGetsFreshIdentityAndOneTerminalResult(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(events);
        runner.start(new WaitTask(100), "before_restore");
        long priorExecution =
                events.named(LogEvents.TASK_START).getFirst().context().execution();
        runner.stop("save boundary");

        var savedQueue = new JobQueue(QueueLimits.DEFAULTS);
        Job active = savedQueue.addDirect(
                new QueuedStep("wait", new JsonObject(), null, List.of(), GuardAction.WAIT, null, 1, List.of()));
        savedQueue.markRunning(active.id());
        runner.installRestoredState(
                new QueueSave.Decoded(savedQueue, active.id()),
                java.util.Map.of(active.id(), context -> new WaitTask(1)),
                java.util.Map.of(),
                new WaitTask(1));

        TaskLogEvent restoredStart = events.named(LogEvents.TASK_START).getLast();
        h.assertTrue(restoredStart.context().execution() > priorExecution, "restore should allocate a fresh execution");
        h.assertValueEqual(restoredStart.facts().get("origin"), "restore", "restored start origin");
        runner.tick();
        h.assertValueEqual(
                events.finishedDispositions, List.of(TaskDisposition.SUCCESS), "one restored terminal result");
        h.succeed();
    }

    @GameTest(maxTicks = 1200)
    public void nestedObtainForgetsAConfirmedEmptySiteBeforeItsNextPlan(GameTestHelper h) {
        GameTestSupport.platformAt(h, 500, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 501, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 501, 2);
        companion.setOwner(owner.getUUID());
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        dev.friendai.entity.CompanionManager.get(h.getLevel().getServer()).register(companion);
        runtime.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();
        var remembered = h.absolutePos(new net.minecraft.core.BlockPos(5, 501, 2));
        var viewpoint = h.absolutePos(new net.minecraft.core.BlockPos(2, 501, 2));
        long siteId = runtime.knowledge()
                .book(owner.getUUID())
                .observe(
                        new dev.friendai.features.knowledge.ResourceObservation(
                                "minecraft:oak_log",
                                new dev.friendai.features.knowledge.ResourceObservation.Location(
                                        dimension, new Pos(remembered.getX(), remembered.getY(), remembered.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                Math.max(0, h.getLevel().getGameTime() - 1)),
                        "minecraft:oak_log");
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setEventSink(events);
        runner.start(obtainLogs(), "obtain_empty_site");
        int[] stage = {0};
        int[] priorVisits = {0};
        h.succeedWhen(() -> {
            runner.tick();
            long visits = events.named(LogEvents.SPAN_START).stream()
                    .filter(event -> "visitresource".equals(event.facts().get("operation")))
                    .count();
            if (stage[0] == 0) {
                h.assertTrue(!events.finishedDispositions.isEmpty(), "waiting for the first obtain to settle");
                h.assertValueEqual(
                        events.finishedDispositions.getFirst(),
                        TaskDisposition.FAILED,
                        "the nested obtain must replan after the missing source");
                h.assertTrue(visits == 1, "the first obtain should visit one remembered site, got " + visits);
                h.assertTrue(
                        runtime.knowledge()
                                .book(owner.getUUID())
                                .site(siteId, h.getLevel().getGameTime() + 1)
                                .map(site -> site.members().isEmpty())
                                .orElse(true),
                        "the nested terminal hook must forget the directly confirmed empty site");
                priorVisits[0] = (int) visits;
                stage[0] = 1;
                events.finishedDispositions.clear();
                runner.start(obtainLogs(), "obtain_again");
            }
            h.assertTrue(!events.finishedDispositions.isEmpty(), "waiting for the second obtain to settle");
            h.assertValueEqual(visits, (long) priorVisits[0], "a later acquisition does not revisit the empty site");
        });
    }

    private static ObtainTask obtainLogs() {
        return obtainLogs(new MinecraftObtainSubtaskFactory());
    }

    private static ObtainTask obtainLogs(ObtainSubtaskFactory subtaskFactory) {
        return new ObtainTask(
                "minecraft:oak_log", 1, false, true, 4, 1_000, 32, 32, 400, "local", null, null, subtaskFactory);
    }

    /**
     * The same remembered, now empty site for the lifecycle tests: only the memory exists, so the first plan is a
     * visit and the visit can only prove the block gone.
     */
    private static Remembered rememberLogSite(GameTestHelper h) {
        GameTestSupport.platformAt(h, 500, 8);
        var owner = GameTestSupport.mockPlayerAt(h, 2, 501, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 501, 2);
        companion.setOwner(owner.getUUID());
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        dev.friendai.entity.CompanionManager.get(h.getLevel().getServer()).register(companion);
        runtime.observations().register(companion);
        String dimension = h.getLevel().dimension().identifier().toString();
        var remembered = h.absolutePos(new net.minecraft.core.BlockPos(5, 501, 2));
        var viewpoint = h.absolutePos(new net.minecraft.core.BlockPos(2, 501, 2));
        long siteId = runtime.knowledge()
                .book(owner.getUUID())
                .observe(
                        new ResourceObservation(
                                "minecraft:oak_log",
                                new ResourceObservation.Location(
                                        dimension, new Pos(remembered.getX(), remembered.getY(), remembered.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                Math.max(0, h.getLevel().getGameTime() - 1)),
                        "minecraft:oak_log");
        return new Remembered(runtime.knowledge(), owner.getUUID(), companion, siteId);
    }

    private record Remembered(KnowledgeStore knowledge, UUID owner, CompanionEntity companion, long siteId) {
        boolean forgotten(GameTestHelper h) {
            return knowledge
                    .book(owner)
                    .site(siteId, h.getLevel().getGameTime() + 1)
                    .map(site -> site.members().isEmpty())
                    .orElse(true);
        }
    }

    private static long visitSpans(Events events) {
        return events.named(LogEvents.SPAN_START).stream()
                .filter(event -> "visitresource".equals(event.facts().get("operation")))
                .count();
    }

    /**
     * The batch's gather child is an ObtainTask of its own: when it settles naturally, its visit publishes the same
     * proven absence a standalone visit does, so the batch's next plan does not chase the empty site.
     */
    @GameTest(maxTicks = 1200)
    public void batchObtainForgetsAConfirmedEmptySite(GameTestHelper h) {
        Remembered remembered = rememberLogSite(h);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(remembered.companion(), () -> 1200, () -> false);
        runner.setEventSink(events);
        runner.start(
                new BatchObtainTask(
                        List.of(new CraftPlanner.Target("minecraft:oak_log", 1)),
                        false,
                        true,
                        4,
                        1_000,
                        32,
                        32,
                        400,
                        "local",
                        null,
                        null,
                        new MinecraftObtainSubtaskFactory()),
                "obtain_batch_empty_site");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(!events.finishedDispositions.isEmpty(), "waiting for the batch to finish");
            h.assertTrue(visitSpans(events) >= 1, "the batch should visit the remembered site before giving up");
            h.assertTrue(remembered.forgotten(h), "the batch's natural child settlement must publish the absence");
        });
    }

    /**
     * A batch that runs out of time settles the child it was still running, and that child settles its own step: the
     * terminal hook reaches the whole chain instead of stopping at the first level.
     */
    @GameTest(maxTicks = 600)
    public void aBatchTimeoutSettlesItsRunningChild(GameTestHelper h) {
        Remembered remembered = rememberLogSite(h);
        StubSubtaskFactory factory = new StubSubtaskFactory(TaskStatus.RUNNING);
        ExecutionCoordinator runner = new ExecutionCoordinator(remembered.companion(), () -> 600, () -> false);
        runner.setEventSink(new Events());
        runner.start(
                new BatchObtainTask(
                        List.of(new CraftPlanner.Target("minecraft:oak_log", 1)),
                        false,
                        true,
                        1,
                        5,
                        32,
                        32,
                        400,
                        "local",
                        null,
                        null,
                        factory),
                "obtain_batch_timeout");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(!factory.children.isEmpty(), "waiting for the batch to start its gather child");
            TrackedChild running = factory.children.getLast();
            h.assertValueEqual(running.stops, 1, "the batch timeout stops the child it was running");
            h.assertValueEqual(running.terminals, 1, "the batch timeout releases that child's terminal effects");
        });
    }

    /**
     * A batch settles a craft child that ends on its own, so the child's terminal effects run once: a batch whose
     * craft child fails must still release what that child owned before it reports the failure.
     */
    @GameTest(maxTicks = 600)
    public void aBatchCraftFailureSettlesItsChild(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
        StubSubtaskFactory factory = new StubSubtaskFactory(TaskStatus.FAILED);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setEventSink(new Events());
        runner.start(
                new BatchObtainTask(List.of(new CraftPlanner.Target("minecraft:stick", 1)), false, false, factory),
                "obtain_batch_craft");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(!factory.children.isEmpty(), "waiting for the batch's craft child");
            TrackedChild crafted = factory.children.getLast();
            h.assertValueEqual(crafted.stops, 1, "the batch stops the craft child that ended");
            h.assertValueEqual(crafted.terminals, 1, "the batch runs that child's terminal effects once");
        });
    }

    /**
     * A cancelled visit is not a verdict. The owner stopped the work before any ray settled, so the remembered site
     * keeps every member — the terminal hook that would erase them belongs to natural settlement only.
     */
    @GameTest(maxTicks = 600)
    public void aCancelledNestedVisitKeepsTheRememberedSite(GameTestHelper h) {
        Remembered remembered = rememberLogSite(h);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(remembered.companion(), () -> 600, () -> false);
        runner.setEventSink(events);
        runner.start(obtainLogs(), "obtain_cancelled_visit");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(visitSpans(events) >= 1, "waiting for the visit to start");
            runner.stop("test cleanup");
            h.assertFalse(remembered.forgotten(h), "a cancelled visit must not erase the remembered site");
        });
    }

    /**
     * A nested child earns the lifecycle the runner gives a top-level task: a natural finish stops its cleanup and
     * runs its terminal hook once, while the child still running at cancellation is stopped without terminal effects.
     */
    @GameTest(maxTicks = 600)
    public void aNestedChildSettlesWithTheLifecycleItEarned(GameTestHelper h) {
        Remembered remembered = rememberLogSite(h);
        StubSubtaskFactory factory = new StubSubtaskFactory(TaskStatus.SUCCESS);
        ExecutionCoordinator runner = new ExecutionCoordinator(remembered.companion(), () -> 600, () -> false);
        runner.setEventSink(new Events());
        runner.start(obtainLogs(factory), "obtain_tracked_child");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(factory.children.size() >= 2, "waiting for the parent to plan its next child");
            TrackedChild settled = factory.children.getFirst();
            h.assertValueEqual(settled.stops, 1, "a naturally settled child releases its cleanup once");
            h.assertValueEqual(settled.terminals, 1, "a naturally settled child runs its terminal hook once");
            runner.stop("test cleanup");
            TrackedChild cancelled = factory.children.getLast();
            h.assertValueEqual(cancelled.stops, 1, "cancellation still stops the running child");
            h.assertValueEqual(cancelled.terminals, 0, "cancellation must not run terminal effects");
            h.succeed();
        });
    }

    /** A child whose start throws is a failed start, not a silent leak: the parent settles it once on the way down. */
    @GameTest(maxTicks = 600)
    public void aNestedChildWhoseStartFailsStillSettles(GameTestHelper h) {
        Remembered remembered = rememberLogSite(h);
        StartFailingSubtaskFactory factory = new StartFailingSubtaskFactory();
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(remembered.companion(), () -> 600, () -> false);
        runner.setEventSink(events);
        runner.start(obtainLogs(factory), "obtain_failed_start");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(!events.finishedDispositions.isEmpty(), "waiting for the failed start to settle");
            h.assertValueEqual(
                    events.finishedDispositions.getFirst(),
                    TaskDisposition.FAILED,
                    "a child that cannot start fails the started task");
            h.assertValueEqual(factory.child.stops, 1, "a failed-start child is stopped once");
            h.assertValueEqual(factory.child.terminals, 1, "a failed-start child still runs its terminal hook once");
            h.succeed();
        });
    }

    /** Hands every plan step the same counted child, so settlement effects are observable. */
    private static final class StubSubtaskFactory implements ObtainSubtaskFactory {
        final List<TrackedChild> children = new ArrayList<>();
        private final TaskStatus status;

        private StubSubtaskFactory(TaskStatus status) {
            this.status = status;
        }

        @Override
        public Created create(TaskContext context, ObtainStep step, Options options) {
            TrackedChild child = new TrackedChild(status);
            children.add(child);
            return Created.task(child);
        }
    }

    /** A child whose cancellation and terminal effects are told apart by counting them. */
    private static final class TrackedChild implements Task {
        private final TaskStatus status;
        int stops;
        int terminals;

        private TrackedChild(TaskStatus status) {
            this.status = status;
        }

        @Override
        public String type() {
            return "tracked_child";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return status;
        }

        @Override
        public void stop(TaskContext c) {
            stops++;
        }

        @Override
        public void onTerminal(TaskContext c) {
            terminals++;
        }

        @Override
        public String describe() {
            return "tracked child";
        }

        @Override
        public String resultSummary() {
            return "tracked child " + status;
        }
    }

    private static final class StartFailingSubtaskFactory implements ObtainSubtaskFactory {
        final StartFailingChild child = new StartFailingChild();

        @Override
        public Created create(TaskContext context, ObtainStep step, Options options) {
            return Created.task(child);
        }
    }

    private static final class StartFailingChild implements Task {
        int stops;
        int terminals;

        @Override
        public String type() {
            return "start_failing_child";
        }

        @Override
        public void start(TaskContext c) {
            throw new IllegalStateException("cannot start");
        }

        @Override
        public TaskStatus tick(TaskContext c) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {
            stops++;
        }

        @Override
        public void onTerminal(TaskContext c) {
            terminals++;
        }

        @Override
        public String describe() {
            return "start failing child";
        }

        @Override
        public String resultSummary() {
            return "";
        }
    }

    /**
     * A clear is one transition: the running job stops, the plan finishes, the cancellation reaches the owner and the
     * queue is empty afterwards. Removing any of those steps leaves one of the assertions red.
     */
    @GameTest(maxTicks = 60)
    public void clearQueueStopsTheRunningJobAndEmptiesTheQueue(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        List<String> reported = new ArrayList<>();
        runner.setEventSink(new TaskEventSink() {
            @Override
            public void onFinished(int id, String toolName, TaskStatus status, String summary) {}

            @Override
            public void onCancelled(int id, String toolName, String reason) {
                reported.add(toolName + ":CANCELLED:" + reason);
            }

            @Override
            public void onJobFinished(long jobId, long planId, String toolName, TaskStatus status, String summary) {
                reported.add(toolName + ":" + status + ":" + summary);
            }

            @Override
            public void onJobFinished(
                    long jobId,
                    long planId,
                    String toolName,
                    TaskDisposition disposition,
                    String summary,
                    long execution) {
                reported.add(toolName + ":" + disposition + ":" + summary);
            }
        });
        runner.enqueuePlan(
                "clear test",
                List.of(new QueuedStep(
                        "stay", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of())),
                List.of(context -> new StayTask()),
                PlanOptions.DEFAULT);
        boolean[] cleared = {false};
        h.succeedWhen(() -> {
            if (!cleared[0]) {
                runner.tick();
                h.assertTrue(runner.current().isPresent(), "the queued step never started");
                String result = runner.clearQueue("test clear");
                h.assertTrue(!result.isBlank(), "the clear reported nothing");
                cleared[0] = true;
            }
            h.assertTrue(
                    runner.queue().jobCount() == 0,
                    "the queue still holds jobs: " + runner.queue().jobCount());
            h.assertTrue(runner.queue().plans().isEmpty(), "the plan survived the clear");
            h.assertTrue(runner.current().isEmpty(), "the active task survived the clear");
            h.assertTrue(
                    reported.size() == 1 && reported.getFirst().startsWith("stay:CANCELLED"),
                    "the stopped job should reach the owner once as a cancellation: " + reported);
        });
    }

    /**
     * A skip takes the named step out of its plan and nothing else: the rest of the plan stays queued and runs once
     * the plan is released. A skip that dropped the plan, or left the step pending, leaves this red.
     */
    @GameTest(maxTicks = 60)
    public void skippingAQueuedJobLeavesTheRestOfItsPlan(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        long planId = runner.enqueuePlan(
                "skip test",
                List.of(
                        new QueuedStep(
                                "stay",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of()),
                        new QueuedStep(
                                "wait",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                List.of(context -> new StayTask(), context -> new WaitTask(200)),
                PlanOptions.DEFAULT,
                id -> runner.queue().setHold(HoldKey.plan(id), QueueHold.pause("test setup")));
        List<Job> jobs = runner.queue().jobsOfPlan(planId);
        long firstId = jobs.get(0).id();
        long secondId = jobs.get(1).id();
        JobQueue.Result result = runner.skipJob(firstId);
        h.assertTrue(result.ok(), "the skip was refused: " + result.text());
        h.assertValueEqual(runner.queue().job(firstId).orElseThrow().state(), JobState.SKIPPED, "the skipped step");
        h.assertValueEqual(runner.queue().job(secondId).orElseThrow().state(), JobState.PENDING, "the next step");

        runner.queue().release(HoldKey.plan(planId));
        runner.wakeQueue();
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(
                    runner.currentToolName().equals("wait"),
                    "the rest of the plan never started: " + runner.currentToolName());
        });
    }

    @GameTest
    public void defaultFollowDoesNotExpire(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 1, () -> true);

        runner.tick();
        h.assertTrue(runner.currentIsDefaultActivity(), "the idle follow should start as the default activity");
        for (int i = 0; i < 25; i++) runner.tick();

        h.assertTrue(
                runner.currentIsDefaultActivity()
                        && runner.current()
                                .map(Task::type)
                                .filter("follow"::equals)
                                .isPresent(),
                "the default follow must remain active past the one-second task timeout");
        h.succeed();
    }
}
