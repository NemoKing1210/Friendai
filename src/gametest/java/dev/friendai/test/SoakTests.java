package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.navigation.minecraft.task.StayTask;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.TaskEventSink;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** A short opt-in runner soak: long enough to catch a hang, a duplicate start or a terminal event leak. */
public class SoakTests {
    @GameTest(maxTicks = 2400)
    public void aLongRunnerSessionFinishesAndCanBeCancelled(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Recorder recorder = new Recorder();
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setEventSink(recorder);

        runner.start(new SoakTask(300), "soak");
        for (int i = 0; i < 2000 && recorder.finalStatus == null; i++) {
            runner.tick();
        }
        h.assertValueEqual(recorder.finalStatus, TaskStatus.SUCCESS, "soak task did not finish");
        h.assertValueEqual(recorder.finished, 1, "one terminal event");
        h.assertValueEqual(runner.queue().jobCount(), 0, "the queue must be empty after success");

        runner.start(new StayTask(), "stay");
        runner.tick();
        runner.stop("soak cancel");
        h.assertValueEqual(recorder.cancelled, 1, "one cancellation");
        for (int i = 0; i < 20; i++) {
            runner.tick();
        }
        h.assertValueEqual(recorder.cancelled, 1, "cancellation must not repeat");
        h.succeed();
    }

    private static final class SoakTask implements Task {
        private final int finishAt;
        private int ticks;
        private long stamp;

        SoakTask(int finishAt) {
            this.finishAt = finishAt;
        }

        @Override
        public String type() {
            return "soak";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            ticks++;
            if (ticks % 20 == 0) {
                stamp++;
            }
            return ticks >= finishAt ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "soaking";
        }

        @Override
        public String resultSummary() {
            return "soaked";
        }

        @Override
        public long progressStamp() {
            return stamp;
        }
    }

    private static final class Recorder implements TaskEventSink {
        TaskStatus finalStatus;
        int finished;
        int cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            finished++;
            finalStatus = status;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }
    }
}
