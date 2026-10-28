package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.StayTask;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class ActiveTaskLifecycleTests {
    private static final class Events implements TaskListener {
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

    @GameTest
    public void reentrantReplacementDoesNotCompleteOrEraseTheReplacedTask(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setListener(events);
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
        runner.setListener(events);
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
