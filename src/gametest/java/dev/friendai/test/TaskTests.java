package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.debug.DebugAction;
import dev.friendai.debug.DebugTarget;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskListener;
import dev.friendai.task.TaskRunner;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.builtin.CheckContainerTask;
import dev.friendai.task.builtin.GoToTask;
import dev.friendai.task.builtin.MineBlockAtTask;
import dev.friendai.task.builtin.PlaceBlockTask;
import dev.friendai.task.builtin.StayTask;
import dev.friendai.task.builtin.UseBlockTask;
import dev.friendai.task.plan.PlanEntry;
import dev.friendai.task.plan.SequenceTask;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.core.BlockPos;
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

    /** A plan step that hands the runner a cleanup task when cancelled, like a build with its scaffold. */
    private static final class CleanupStep implements Task {
        private final Task cleanup;

        CleanupStep(Task cleanup) {
            this.cleanup = cleanup;
        }

        @Override
        public String type() {
            return "cleanup_step";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "cleanup step";
        }

        @Override
        public String resultSummary() {
            return "";
        }

        @Override
        public Task cleanupAfterCancel(TaskContext c) {
            return cleanup;
        }
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

    @GameTest
    public void fixedBlockTasksExposeDebugTargets(GameTestHelper h) {
        BlockPos target = new BlockPos(4, 5, 6);
        h.assertTrue(new StayTask().debugTargets().isEmpty(), "tasks without a block action have no debug targets");
        h.assertTrue(new MineBlockAtTask(target).debugTargets().equals(
                List.of(new DebugTarget(4, 5, 6, DebugAction.BREAK, ""))), "mine target should be exposed");
        h.assertTrue(new PlaceBlockTask((net.minecraft.world.item.BlockItem) Blocks.DIRT.asItem(), target)
                .debugTargets().equals(List.of(new DebugTarget(4, 5, 6, DebugAction.PLACE, "minecraft:dirt"))),
                "explicit placement position should be exposed before walking");
        h.assertTrue(new UseBlockTask(target, null).debugTargets().equals(
                List.of(new DebugTarget(4, 5, 6, DebugAction.USE, ""))), "use target should be exposed");
        h.assertTrue(new CheckContainerTask(target).debugTargets().equals(
                List.of(new DebugTarget(4, 5, 6, DebugAction.USE, "container"))),
                "container target should be exposed by the base task");
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void goToReachesTarget(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        runner.start(new GoToTask(h.absoluteVec(new Vec3(10, 2, 2))), "go_to");
        runner.start(new StayTask(), "stay");
        h.assertTrue(events.log.equals(List.of("cancelled:go_to:replaced by a new task")), "events were " + events.log);
        h.succeed();
    }

    @GameTest
    public void stopNamedStopsOnlyAMatchingTask(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, true);
        runner.start(new StayTask(), "mine_blocks");
        h.assertTrue(runner.stopNamed("go_to", "x").isEmpty(), "a different name must not stop the task");
        h.assertTrue(runner.current().isPresent() && runner.current().get().type().equals("stay"), "the task should still run");
        h.assertTrue(events.log.isEmpty(), "nothing was cancelled, events were " + events.log);
        var stopped = runner.stopNamed("mine_blocks", "x");
        h.assertTrue(stopped.isPresent() && stopped.get().equals("staying in place"), "stopped was " + stopped);
        h.assertTrue(runner.current().isPresent() && runner.current().get().type().equals("follow"), "should be back on the default activity");
        h.assertTrue(events.log.equals(List.of("cancelled:mine_blocks:x")), "events were " + events.log);
        h.assertTrue(runner.stopNamed("mine_blocks", "x").isEmpty(), "nothing named mine_blocks is running any more");
        h.succeed();
    }

    @GameTest
    public void stopNamedMatchesTheRunningPlanStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, true);
        for (String name : List.of("mine_blocks", "run_plan")) {
            runner.start(new SequenceTask(null, List.of(
                    new PlanEntry("mine_blocks", ctx -> new StayTask()),
                    new PlanEntry("craft_item", ctx -> new StayTask())), () -> 300), "run_plan");
            runner.tick();
            h.assertTrue(runner.stopNamed("craft_item", "x").isEmpty(), "a queued step must not match");
            h.assertTrue(runner.current().isPresent() && runner.current().get().type().equals("run_plan"), "the plan should still run");
            h.assertTrue(runner.stopNamed(name, "x").isPresent(), name + " should stop the plan");
            h.assertTrue(runner.current().isPresent() && runner.current().get().type().equals("follow"), "should be back on the default activity after " + name);
        }
        h.succeed();
    }

    @GameTest
    public void planCancelRunsTheStepsCleanup(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        Task cleanup = new StayTask();
        runner.start(new SequenceTask(null, List.of(
                new PlanEntry("cleanup_step", ctx -> new CleanupStep(cleanup))), () -> 300), "run_plan");
        runner.tick();
        runner.stop("x");
        h.assertTrue(runner.current().isPresent() && runner.current().get() == cleanup,
                "the plan must hand the runner its step's cleanup, current: "
                        + runner.current().map(Task::type).orElse("none"));
        h.succeed();
    }

    @GameTest
    public void stopFallsBackToFollow(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 1, false);
        // Too far to arrive within the one-second timeout, and nothing in the way: the timeout must be what fails it.
        runner.start(new GoToTask(h.absoluteVec(new Vec3(14.5, 2, 14.5))), "go_to");
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
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
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
        CompanionEntity a = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        a.taskRunner().start(new StayTask(), "stay");
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        CompoundTag tag = out.buildResult();

        CompanionEntity b = h.spawn(FriendaiEntities.COMPANION, 4, 2, 4);
        b.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(b.taskRunner().current().isPresent() && b.taskRunner().current().get().type().equals("stay"),
                "stay task not restored");
        h.succeed();
    }

    /** A task that only counts how many times it was ticked. */
    private static final class CountingTask implements Task {
        int ticks;

        @Override
        public String type() {
            return "counting";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            ticks++;
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public String resultSummary() {
            return "";
        }
    }

    private static void tick(TaskRunner runner, int times) {
        for (int i = 0; i < times; i++) {
            runner.tick();
        }
    }

    @GameTest(maxTicks = 400)
    public void pausedTaskDoesNotTick(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        CountingTask task = new CountingTask();
        runner.start(task, "counting");
        tick(runner, 10);
        h.assertTrue(task.ticks == 10, "ticks before pause: " + task.ticks);

        runner.setPaused(true);
        tick(runner, 50);
        h.assertTrue(task.ticks == 10, "ticks while paused: " + task.ticks);

        runner.setPaused(false);
        tick(runner, 5);
        h.assertTrue(task.ticks == 15, "ticks after unpause: " + task.ticks);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void pauseStopsTimeoutClock(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 2, false);
        runner.start(new CountingTask(), "counting");
        tick(runner, 20);
        runner.setPaused(true);
        tick(runner, 100);
        runner.setPaused(false);
        tick(runner, 20);
        h.assertTrue(runner.current().isPresent(), "task should still be running: " + events.log);
        h.assertTrue(events.finalStatus == null, "task must not time out while paused: " + events.log);
        h.succeed();
    }

    @GameTest(maxTicks = 400)
    public void newTaskDuringPauseStartsAfterFight(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        Events events = new Events();
        TaskRunner runner = runner(c, events, 300, false);
        CountingTask first = new CountingTask();
        runner.start(first, "first");
        runner.setPaused(true);
        tick(runner, 5);
        h.assertTrue(first.ticks == 0, "the paused task must not tick: " + first.ticks);

        CountingTask second = new CountingTask();
        runner.start(second, "second");
        tick(runner, 10);
        h.assertTrue(second.ticks == 0, "a task started while paused must wait: " + second.ticks);
        h.assertTrue(events.log.equals(List.of("cancelled:first:replaced by a new task")), "events were " + events.log);

        runner.setPaused(false);
        tick(runner, 3);
        h.assertTrue(second.ticks == 3, "ticks after unpause: " + second.ticks);
        h.succeed();
    }
}
