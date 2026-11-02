package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
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
