package dev.friedai.test;

import dev.friedai.entity.CompanionEntity;
import dev.friedai.entity.FriedaiEntities;
import dev.friedai.task.Task;
import dev.friedai.task.TaskContext;
import dev.friedai.task.TaskListener;
import dev.friedai.task.TaskRunner;
import dev.friedai.task.TaskStatus;
import dev.friedai.task.builtin.DropItemsTask;
import dev.friedai.task.inventory.ItemMatcher;
import dev.friedai.task.plan.PlanEntry;
import dev.friedai.task.plan.SequenceTask;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class PlanTests {
    private static final class Result implements TaskListener {
        TaskStatus status;
        String summary;
        int cancelled;

        @Override
        public void onFinished(int id, String toolName, TaskStatus status, String summary) {
            this.status = status;
            this.summary = summary;
        }

        @Override
        public void onCancelled(int id, String toolName, String reason) {
            cancelled++;
        }
    }

    private static TaskRunner runner(CompanionEntity c, Result result) {
        TaskRunner r = new TaskRunner(c, () -> 300, () -> false);
        r.setListener(result);
        return r;
    }

    private static PlanEntry drop(String item, int count) {
        return new PlanEntry("drop_items", c -> new DropItemsTask(ItemMatcher.parse(item).orElseThrow(), count));
    }

    /** A step that never finishes, so the plan's stop and timeout paths can be checked. */
    private static final class NeverEnding implements Task {
        private final boolean hasTimeout;
        boolean stopped;

        NeverEnding(boolean hasTimeout) {
            this.hasTimeout = hasTimeout;
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            return TaskStatus.RUNNING;
        }

        @Override
        public void stop(TaskContext c) {
            stopped = true;
        }

        @Override
        public String describe() {
            return "waiting";
        }

        @Override
        public String resultSummary() {
            return "";
        }

        @Override
        public boolean hasTimeout() {
            return hasTimeout;
        }
    }

    /** A step that crashes while ticking. */
    private static final class Throwing implements Task {
        @Override
        public String type() {
            return "fake";
        }

        @Override
        public void start(TaskContext c) {}

        @Override
        public TaskStatus tick(TaskContext c) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void stop(TaskContext c) {}

        @Override
        public String describe() {
            return "throwing";
        }

        @Override
        public String resultSummary() {
            return "";
        }
    }

    @GameTest
    public void twoInstantStepsSucceedInOrder(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.TUFF, 3));
        c.inventory().addItem(new ItemStack(Items.CALCITE, 2));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new SequenceTask("clean up", List.of(drop("minecraft:tuff", 3), drop("minecraft:calcite", 2)), () -> 300), "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.SUCCESS, "not finished: " + result.status + " " + result.summary);
            h.assertTrue(result.summary.equals("plan \"clean up\" done: 1) dropped 3 minecraft:tuff; 2) dropped 2 minecraft:calcite"),
                    "summary was " + result.summary);
        });
    }

    @GameTest
    public void failingStepStopsPlan(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.BASALT, 1));
        c.inventory().addItem(new ItemStack(Items.BLACKSTONE, 4));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new SequenceTask(null, List.of(
                drop("minecraft:basalt", 1), drop("minecraft:smooth_basalt", 1), drop("minecraft:blackstone", 4)), () -> 300), "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not failed yet: " + result.status);
            h.assertTrue(result.summary.equals(
                    "plan stopped at step 2/3 (drop_items): I don't have minecraft:smooth_basalt; done before: 1) dropped 1 minecraft:basalt"),
                    "summary was " + result.summary);
            h.assertTrue(c.inventory().countItem(Items.BLACKSTONE) == 4, "step 3 must not run");
        });
    }

    @GameTest
    public void factoryErrorFailsStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        PlanEntry broken = new PlanEntry("drop_items", ctx -> {
            throw new IllegalArgumentException("unknown item: x:y");
        });
        runner.start(new SequenceTask(null, List.of(broken), () -> 300), "run_plan");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.equals("plan stopped at step 1/1 (drop_items): unknown item: x:y; done before: nothing"),
                "summary was " + result.summary);
        h.succeed();
    }

    @GameTest
    public void crashingStepIsIsolated(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        c.inventory().addItem(new ItemStack(Items.ANDESITE, 1));
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        runner.start(new SequenceTask(null, List.of(new PlanEntry("fake", ctx -> new Throwing())), () -> 300), "run_plan");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.FAILED, "should fail");
        h.assertTrue(result.summary.endsWith("(fake): task crashed: IllegalStateException; done before: nothing"), "summary was " + result.summary);
        runner.start(new DropItemsTask(ItemMatcher.parse("minecraft:andesite").orElseThrow(), 1), "drop_items");
        runner.tick();
        h.assertTrue(result.status == TaskStatus.SUCCESS, "the runner must keep working, got " + result.summary);
        h.succeed();
    }

    @GameTest
    public void stopStopsActiveStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        NeverEnding fake = new NeverEnding(true);
        runner.start(new SequenceTask(null, List.of(new PlanEntry("fake", ctx -> fake)), () -> 300), "run_plan");
        runner.tick();
        runner.tick();
        runner.stop("test");
        h.assertTrue(fake.stopped, "the active step must be stopped");
        h.assertTrue(result.cancelled == 1, "one cancel event, got " + result.cancelled);
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void stepTimeout(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        NeverEnding fake = new NeverEnding(true);
        runner.start(new SequenceTask("t", List.of(new PlanEntry("fake", ctx -> fake)), () -> 20), "run_plan");
        h.succeedWhen(() -> {
            runner.tick();
            h.assertTrue(result.status == TaskStatus.FAILED, "not yet: " + result.status);
            h.assertTrue(result.summary.equals("plan \"t\" stopped at step 1/1 (fake): timed out; done before: nothing"),
                    "summary was " + result.summary);
            h.assertTrue(fake.stopped, "the timed-out step must be stopped");
        });
    }

    @GameTest
    public void describeShowsCurrentStep(GameTestHelper h) {
        CompanionEntity c = h.spawn(FriedaiEntities.COMPANION, 2, 2, 2);
        Result result = new Result();
        TaskRunner runner = runner(c, result);
        SequenceTask plan = new SequenceTask("g", List.of(
                new PlanEntry("fake", ctx -> new NeverEnding(true)), new PlanEntry("fake", ctx -> new NeverEnding(true))), () -> 300);
        runner.start(plan, "run_plan");
        runner.tick();
        h.assertTrue(plan.describe().equals("plan \"g\" step 1/2: waiting"), "describe was " + plan.describe());
        h.succeed();
    }
}
