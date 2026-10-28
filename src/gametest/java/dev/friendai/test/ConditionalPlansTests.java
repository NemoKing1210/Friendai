package dev.friendai.test;

import com.google.gson.JsonObject;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.ExecutionHold;
import dev.friendai.server.execution.HoldReason;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.plan.PlanStepFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Server-world acceptance tests for deterministic conditional plan execution. */
public final class ConditionalPlansTests {
    @GameTest
    public void selectsTheBranchFromARealForwardBlock(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var tree = companion.blockPosition().relative(companion.getDirection()).above();
        h.getLevel().setBlock(tree, Blocks.OAK_LOG.defaultBlockState(), 3);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        PlanDefinition definition = new PlanDefinition(List.of(new PlanNode.Conditional(
                new Guard.BlockInFront("minecraft:oak_log", 1),
                List.of(action("then_action")),
                List.of(action("else_action")))));
        runner.enqueuePlan(
                "choose branch",
                definition,
                PlanOptions.DEFAULT,
                Map.of("then_action", factory("then_action", started), "else_action", factory("else_action", started)));

        h.succeedWhen(() -> {
            runner.tick();
            if (runner.queue().planCount() == 0) {
                h.assertTrue(
                        started.equals(List.of("then_action")),
                        "the forward oak-log branch should execute: " + started);
                h.assertTrue(
                        runner.queue().archives().getLast().success(), "selected branch should complete successfully");
            }
        });
    }

    @GameTest
    public void holdPausesAnActiveStructuredAction(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        int[] ticks = {0};
        long plan = runner.enqueuePlan(
                "held plan",
                new PlanDefinition(List.of(action("held_action"))),
                PlanOptions.DEFAULT,
                Map.of("held_action", (context, step) -> new Task() {
                    @Override
                    public String type() {
                        return "held_action";
                    }

                    @Override
                    public void start(TaskContext context) {}

                    @Override
                    public TaskStatus tick(TaskContext context) {
                        return ++ticks[0] > 1 ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
                    }

                    @Override
                    public void stop(TaskContext context) {}

                    @Override
                    public String describe() {
                        return "held action";
                    }

                    @Override
                    public String resultSummary() {
                        return "done";
                    }
                }));
        ExecutionHold menuHold = runner.acquireHold(HoldReason.MENU);
        ExecutionHold inventoryHold = runner.acquireHold(HoldReason.INVENTORY);
        ExecutionHold combatHold = runner.acquireHold(HoldReason.COMBAT);
        runner.tick();
        h.assertTrue(
                ticks[0] == 0 && runner.queue().plan(plan).isPresent(), "all scopes together must pause the action");
        menuHold.close();
        inventoryHold.close();
        runner.tick();
        h.assertTrue(ticks[0] == 0, "remaining combat scope must still hold the action");
        combatHold.close();
        runner.tick();
        h.assertTrue(
                ticks[0] == 1 && runner.queue().plan(plan).isPresent(),
                "first resumed tick must resume the same action");
        runner.tick();
        h.assertTrue(runner.queue().archives().getLast().success(), "resumed plan should finish successfully");
        h.succeed();
    }

    @GameTest
    public void cancelStopsTheActiveStructuredAction(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        int[] stopped = {0};
        long plan = runner.enqueuePlan(
                "cancel me",
                new PlanDefinition(List.of(action("cancel_action"))),
                PlanOptions.DEFAULT,
                Map.of("cancel_action", (context, step) -> new Task() {
                    @Override
                    public String type() {
                        return "cancel_action";
                    }

                    @Override
                    public void start(TaskContext context) {}

                    @Override
                    public TaskStatus tick(TaskContext context) {
                        return TaskStatus.RUNNING;
                    }

                    @Override
                    public void stop(TaskContext context) {
                        stopped[0]++;
                    }

                    @Override
                    public String describe() {
                        return "cancel action";
                    }

                    @Override
                    public String resultSummary() {
                        return "";
                    }
                }));
        ExecutionHold hold = runner.acquireHold(HoldReason.COMBAT);
        runner.cancelPlan(plan, "test cancellation");
        h.assertTrue(
                runner.queue().plan(plan).isEmpty() && stopped[0] == 1,
                "cancellation should stop the current action and remove its plan");
        h.assertTrue(runner.held(), "cancellation must not release another owner's hold");
        hold.close();
        h.assertTrue(!runner.queue().archives().getLast().success(), "cancelled plan should be archived as failed");
        h.succeed();
    }

    @GameTest
    public void revokedBranchActionFailsAtItsStablePath(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var tree = companion.blockPosition().relative(companion.getDirection()).above();
        h.getLevel().setBlock(tree, Blocks.OAK_LOG.defaultBlockState(), 3);
        ExecutionCoordinator runner = companion.executionCoordinator();
        PlanStepFactory denied = new PlanStepFactory() {
            @Override
            public Task create(TaskContext context, QueuedStep step) {
                throw new AssertionError("denied action must not be created");
            }

            @Override
            public boolean authorized(TaskContext context, QueuedStep step) {
                return false;
            }
        };
        runner.enqueuePlan(
                "revoked branch",
                new PlanDefinition(List.of(new PlanNode.Conditional(
                        new Guard.BlockInFront("minecraft:oak_log", 1),
                        List.of(action("protected_action")),
                        List.of()))),
                PlanOptions.DEFAULT,
                Map.of("protected_action", denied));
        var archive = runner.queue().archives().getLast();
        h.assertTrue(
                !archive.success() && archive.summary().contains("step 1 → then #1"),
                "revoked action should fail once at the selected branch path: " + archive.summary());
        h.succeed();
    }

    @GameTest
    public void repeatStopsAtItsDeclaredCap(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        PlanDefinition definition = new PlanDefinition(List.of(
                new PlanNode.Repeat(List.of(action("repeat_action")), new Guard.HasItem("minecraft:diamond", 1), 2)));
        runner.enqueuePlan(
                "bounded repeat",
                definition,
                PlanOptions.DEFAULT,
                Map.of("repeat_action", factory("repeat_action", started)));

        h.succeedWhen(() -> {
            runner.tick();
            if (runner.queue().planCount() == 0) {
                h.assertTrue(started.size() == 2, "repeat must run exactly to its cap: " + started.size());
                var archive = runner.queue().archives().getLast();
                h.assertTrue(
                        !archive.success() && archive.summary().contains("repeat → until"),
                        "capped repeat should fail at its condition path: " + archive.summary());
            }
        });
    }

    private static PlanNode.Action action(String tool) {
        return new PlanNode.Action(new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of()));
    }

    private static PlanStepFactory factory(String tool, List<String> started) {
        return (context, step) -> new Task() {
            @Override
            public String type() {
                return tool;
            }

            @Override
            public void start(TaskContext context) {
                started.add(tool);
            }

            @Override
            public TaskStatus tick(TaskContext context) {
                return TaskStatus.SUCCESS;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return tool;
            }

            @Override
            public String resultSummary() {
                return "done";
            }
        };
    }
}
