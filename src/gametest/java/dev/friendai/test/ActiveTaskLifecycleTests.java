package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.interaction.minecraft.task.WaitTask;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
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
        Runnable onCancel = () -> {};

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            log.add("finished:" + toolName + ":" + status);
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            log.add("cancelled:" + toolName + ":" + reason);
            onCancel.run();
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
