package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.GoToTask;
import dev.friedai.task.builtin.StayTask;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class TaskTests {
    /** Records everything a runner tells its listener. */
    private static final class Events implements TaskListener {
        final List<String> log = new ArrayList<>();
        TaskStatus finalStatus;
        String finalSummary;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            log.add("finished:" + toolName + ":" + status + ":" + summary);
            finalStatus = status;
            finalSummary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            log.add("cancelled:" + toolName + ":" + reason);
        }
    }

    private static TaskRunner runner(CompanionEntity c, Events events, int timeoutSec, boolean defaultFollow) {
        TaskRunner r = new TaskRunner(c, () -> timeoutSec, () -> defaultFollow);
        r.setListener(events);
        return r;
    }

    private static void wallIn(GameTestHelper h, int cx, int cz) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                for (int y = 2; y <= 5; y++) {
                    boolean inside = x == cx && z == cz && y <= 3;
                    if (!inside) {
                        h.setBlock(x, y, z, Blocks.GLASS);
                    }
                }
            }
        }
    }

    @GameTest(maxTicks = 400)
    public void goToReachesTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        runner.start(new GoToTask(h.absoluteVec(new Vec3(10.5, 2, 2.5))), "go_to");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(events.finalStatus == TaskStatus.SUCCESS, "go_to not finished: " + events.log);
        });
    }

    @GameTest(maxTicks = 400)
    public void goToFailsWhenWalledIn(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        wallIn(h, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        runner.start(new GoToTask(h.absoluteVec(new Vec3(10.5, 2, 2.5))), "go_to");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(events.finalStatus == TaskStatus.FAILED, "not failed yet: " + events.log);
            h.assertTrue(events.finalSummary.startsWith("can't reach"), "summary was " + events.finalSummary);
        });
    }

    @GameTest
    public void newTaskCancelsCurrent(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        runner.start(new GoToTask(h.absoluteVec(new Vec3(10, 2, 2))), "go_to");
        runner.start(new StayTask(), "stay");
        h.assertTrue(events.log.equals(List.of("cancelled:go_to:replaced by a new task")), "events were " + events.log);
        h.succeed();
    }

    @GameTest
    public void stopFallsBackToFollow(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, true);
        runner.start(new StayTask(), "stay");
        runner.stop("x");
        h.assertTrue(runner.current().isPresent() && runner.current().get().type().equals("follow"), "should be following");
        h.assertTrue(events.log.equals(List.of("cancelled:stay:x")), "events were " + events.log);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void timeoutFails(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        wallIn(h, 2, 2);
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 1, false);
        runner.start(new GoToTask(h.absoluteVec(new Vec3(10.5, 2, 2.5))), "go_to");
        int[] ticks = {0};
        h.succeedWhen(() -> {
            runner.tick();
            ticks[0]++;
            h.assertTrue(events.finalStatus == TaskStatus.FAILED, "not failed yet");
            h.assertTrue("timed out".equals(events.finalSummary), "summary was " + events.finalSummary);
            h.assertTrue(ticks[0] <= 25, "took " + ticks[0] + " ticks");
        });
    }

    @GameTest
    public void exceptionInTaskBecomesFailed(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        runner.start(new Task() {
            @Override
            public String type() {
                return "boom";
            }

            @Override
            public void start(TaskContext ctx) {}

            @Override
            public TaskStatus tick(TaskContext ctx) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void stop(TaskContext ctx) {}

            @Override
            public String describe() {
                return "boom";
            }

            @Override
            public String resultSummary() {
                return "";
            }
        }, "boom");
        runner.tick();
        h.assertTrue(events.finalStatus == TaskStatus.FAILED, "should have failed");
        h.assertTrue(events.finalSummary.startsWith("task crashed"), "summary was " + events.finalSummary);
        h.assertTrue(c.isAlive(), "entity should survive");
        h.succeed();
    }

    @GameTest
    public void stayTaskRestoredAfterReload(GameTestHelper h) {
        CompanionEntity a = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        a.taskRunner().start(new StayTask(), "stay");
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriedaiEntities.COMPANION, 4, 2, 4);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.taskRunner().current().isPresent() && b.taskRunner().current().get().type().equals("stay"),
                "stay task not restored");
        h.succeed();
    }
}
