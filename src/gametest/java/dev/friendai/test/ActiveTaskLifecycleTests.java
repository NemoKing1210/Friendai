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
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobQueue;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueueLimits;
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.acquisition.minecraft.task.ObtainTask;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.foundation.diagnostics.DiagnosticClock;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.spatial.Pos;
import dev.friendai.server.execution.DiagnosticClockTestAccess;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.server.execution.tools.MinecraftObtainSubtaskFactory;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

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
                id -> runner.queue().setHold(id, QueueHold.pause("test setup")));
        long jobId = runner.queue().jobsOfPlan(planId).getFirst().id();
        var inserted = runner.queue().insertPrerequisite(jobId, dependent);
        h.assertTrue(inserted.ok(), inserted.text());
        long remedy = runner.queue().jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("wait"))
                .mapToLong(job -> job.id())
                .max()
                .orElseThrow();
        runner.registerFactory(remedy, context -> new WaitTask(1));
        runner.queue().release(planId);
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
                        .setHold(id, QueueHold.defer("later", runner.queue().clock() + 5, null)));
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
    public void aToolGuardGetsOnePickaxePrerequisite(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setRepairFactory((context, args) -> new NoopTask());
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
                id -> runner.queue().setHold(id, QueueHold.pause("owner")));
        runner.tick();
        h.assertTrue(runner.current().isEmpty(), "a paused plan must not start");
        runner.queue().release(planId);
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
        runner.setRepairFactory((context, args) -> new NoopTask());
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
                id -> planRunner.queue().setHold(id, QueueHold.pause("test")));

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
                                "minecraft:coal_ore",
                                new dev.friendai.features.knowledge.ResourceObservation.Location(
                                        dimension, new Pos(remembered.getX(), remembered.getY(), remembered.getZ())),
                                new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                                Math.max(0, h.getLevel().getGameTime() - 1)),
                        "minecraft:coal_ore");
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 600, () -> false);
        runner.setEventSink(events);
        runner.start(obtainCoal(), "obtain_empty_site");
        int[] stage = {0};
        int[] priorVisits = {0};
        h.succeedWhen(() -> {
            runner.tick();
            long visits = events.named(LogEvents.SPAN_START).stream()
                    .filter(event -> "visitresource".equals(event.facts().get("operation")))
                    .count();
            if (stage[0] == 0) {
                if (events.finishedDispositions.isEmpty()) return;
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
                runner.start(obtainCoal(), "obtain_again");
                return;
            }
            if (events.finishedDispositions.isEmpty()) return;
            h.assertValueEqual(visits, (long) priorVisits[0], "a later acquisition does not revisit the empty site");
            h.succeed();
        });
    }

    private static ObtainTask obtainCoal() {
        return new ObtainTask(
                "minecraft:coal",
                1,
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
                new MinecraftObtainSubtaskFactory());
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
                id -> runner.queue().setHold(id, QueueHold.pause("test setup")));
        List<Job> jobs = runner.queue().jobsOfPlan(planId);
        long firstId = jobs.get(0).id();
        long secondId = jobs.get(1).id();
        JobQueue.Result result = runner.skipJob(firstId);
        h.assertTrue(result.ok(), "the skip was refused: " + result.text());
        h.assertValueEqual(runner.queue().job(firstId).orElseThrow().state(), JobState.SKIPPED, "the skipped step");
        h.assertValueEqual(runner.queue().job(secondId).orElseThrow().state(), JobState.PENDING, "the next step");

        runner.queue().release(planId);
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
