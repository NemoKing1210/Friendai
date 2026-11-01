package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.features.social.Mood;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The face answers what the work cost: a finished job pleases, a failed one dispels, a cancel leaves it alone. */
public final class TaskFaceTests {
    private static final class FailingTask implements Task {
        private int ticks;

        @Override
        public String type() {
            return "stay";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return ++ticks >= 3 ? TaskStatus.FAILED : TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "fail on purpose";
        }

        @Override
        public String resultSummary() {
            return "gave up";
        }
    }

    private static final class FinishingTask implements Task {
        private int ticks;

        @Override
        public String type() {
            return "stay";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return ++ticks >= 3 ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "finish on purpose";
        }

        @Override
        public String resultSummary() {
            return "done";
        }
    }

    private static ExecutionCoordinator ready(GameTestHelper h, ServerPlayer owner, CompanionEntity companion) {
        GameTestSupport.platform(h, 8);
        ExecutionCoordinator coordinator = companion.executionCoordinator();
        coordinator.resume();
        return coordinator;
    }

    private static void enqueue(ExecutionCoordinator coordinator, ServerPlayer owner, Task task) {
        coordinator.enqueue(
                new QueuedStep(
                        "stay",
                        new JsonObject(),
                        null,
                        List.of(),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of(),
                        owner.getUUID(),
                        false),
                context -> task);
    }

    @GameTest(maxTicks = 400)
    public void aFailedTaskTurnsTheFaceDispleasedAndCalmReturns(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = ready(h, owner, companion);

        enqueue(coordinator, owner, new FailingTask());
        for (int i = 0; i < 10; i++) {
            coordinator.tick();
        }
        h.assertValueEqual(companion.syncedMood(), Mood.DISPLEASED, "a failed job shows a displeased face");

        // The pulse is measured in game ticks, so let real ticks pass instead of a tight coordinator loop.
        h.succeedWhen(() -> h.assertTrue(
                companion.syncedMood() != Mood.DISPLEASED, "the displeasure expires: " + companion.syncedMood()));
    }

    @GameTest(maxTicks = 400)
    public void aFinishedTaskTurnsTheFacePleased(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = ready(h, owner, companion);

        enqueue(coordinator, owner, new FinishingTask());
        for (int i = 0; i < 10; i++) {
            coordinator.tick();
        }
        h.assertValueEqual(companion.syncedMood(), Mood.PLEASED, "a finished job shows a pleased face");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void aCancelledTaskLeavesTheFaceAlone(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        ExecutionCoordinator coordinator = ready(h, owner, companion);
        Mood before = companion.syncedMood();

        enqueue(coordinator, owner, new FinishingTask());
        coordinator.tick();
        coordinator.stop("owner asked");
        for (int i = 0; i < 10; i++) {
            coordinator.tick();
        }
        h.assertTrue(companion.syncedMood() != Mood.DISPLEASED, "a cancel must not look like a failure");
        h.assertTrue(companion.syncedMood() != Mood.PLEASED, "and must not look like a success: " + before);
        h.succeed();
    }
}
