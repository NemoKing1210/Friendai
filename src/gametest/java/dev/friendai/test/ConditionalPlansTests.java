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
import dev.friendai.execution.queue.QueueSave;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.server.execution.ExecutionHold;
import dev.friendai.server.execution.HoldReason;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.plan.PlanStepFactory;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

    @GameTest(maxTicks = 400)
    public void aGuardedJobWaitsForItsConditionThenRuns(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.setOwner(owner.getUUID());
        ExecutionCoordinator coordinator = companion.executionCoordinator();
        coordinator.resume();
        coordinator.enqueue(
                new QueuedStep(
                        "stay",
                        new JsonObject(),
                        null,
                        List.of(new Guard.HasItem("minecraft:diamond", 1)),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of(),
                        owner.getUUID(),
                        false),
                context -> new dev.friendai.features.navigation.minecraft.task.StayTask(2));

        for (int i = 0; i < 20; i++) {
            coordinator.tick();
        }
        h.assertValueEqual(coordinator.queue().jobCount(), 1, "an unmet guard keeps the job queued, not failed");
        String summary = coordinator.queueSummary();
        h.assertTrue(summary.contains("Blocked"), "the status line must say the work is blocked: " + summary);
        h.assertTrue(
                summary.toLowerCase(java.util.Locale.ROOT).contains("diamond"),
                "the status line must name what it is waiting for: " + summary);
        h.assertTrue(
                !"stay".equals(coordinator.current().map(Task::type).orElse("none")),
                "the guarded task must not start before its condition holds");

        companion.inventory().addItem(new ItemStack(Items.DIAMOND, 1));
        for (int i = 0; i < 80; i++) {
            coordinator.tick();
        }
        h.assertValueEqual(coordinator.queue().jobCount(), 0, "the job runs once its guard holds");
        h.succeed();
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
        ExecutionHold selfCareHold = runner.acquireHold(HoldReason.SELF_CARE);
        ExecutionHold inventoryHold = runner.acquireHold(HoldReason.INVENTORY);
        ExecutionHold combatHold = runner.acquireHold(HoldReason.COMBAT);
        runner.tick();
        h.assertTrue(
                ticks[0] == 0 && runner.queue().plan(plan).isPresent(), "all scopes together must pause the action");
        selfCareHold.close();
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

    @GameTest
    public void aCompletingPlanCarriesItsStepResults(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        runner.enqueuePlan(
                "carry results",
                new PlanDefinition(List.of(action("work"))),
                PlanOptions.DEFAULT,
                Map.of("work", factory("work", started)));

        h.succeedWhen(() -> {
            runner.tick();
            if (runner.queue().planCount() == 0) {
                var archive = runner.queue().archives().getLast();
                h.assertTrue(
                        archive.summary().startsWith("conditional plan completed; #"),
                        "the summary must carry the step results: " + archive.summary());
                h.assertTrue(
                        archive.summary().contains(" work: done"),
                        "the summary must name the step's result: " + archive.summary());
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void aHighPriorityConditionalPlanIsNotStarvedByReadyLowerPriorityJobs(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        companion.inventory().addItem(new ItemStack(Items.DIAMOND, 1));
        List<String> started = new ArrayList<>();
        long lowPlan = runner.queue()
                .addPlan(
                        "busy",
                        List.of(
                                new QueuedStep(
                                        "low_a",
                                        new JsonObject(),
                                        null,
                                        List.of(),
                                        GuardAction.WAIT,
                                        FailurePolicy.ASK,
                                        1,
                                        List.of()),
                                new QueuedStep(
                                        "low_b",
                                        new JsonObject(),
                                        null,
                                        List.of(),
                                        GuardAction.WAIT,
                                        FailurePolicy.ASK,
                                        1,
                                        List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        for (var job : runner.queue().jobsOfPlan(lowPlan)) {
            String tool = job.tool();
            runner.registerFactory(job.id(), context -> {
                started.add(tool);
                return finishingTask(tool);
            });
        }
        // A conditional order at higher priority: it has no emitted job until its interpreter runs, so it can only
        // take the body by being compared with the ready work at the one dispatch boundary.
        runner.enqueuePlan(
                "urgent",
                new PlanDefinition(List.of(new PlanNode.Conditional(
                        new Guard.HasItem("minecraft:diamond", 1), List.of(action("urgent_action")), List.of()))),
                new PlanOptions(5, FailurePolicy.ASK, 0),
                Map.of("urgent_action", (context, step) -> {
                    started.add("urgent_action");
                    return finishingTask("urgent_action");
                }));
        for (int i = 0; i < 6; i++) runner.tick();

        h.assertTrue(!started.isEmpty(), "something must run");
        h.assertValueEqual(
                started.getFirst(),
                "urgent_action",
                "the higher-priority order takes the body before ready lower-priority work: " + started);
        h.assertTrue(
                started.containsAll(List.of("low_a", "low_b")),
                "the low-priority work still runs once the order is done: " + started);
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aBlockedHighPriorityPlanLeavesUnrelatedWorkRunnable(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        long lowPlan = runner.queue()
                .addPlan(
                        "tidy",
                        List.of(new QueuedStep(
                                "low_work",
                                new JsonObject(),
                                null,
                                List.of(),
                                GuardAction.WAIT,
                                FailurePolicy.ASK,
                                1,
                                List.of())),
                        PlanOptions.DEFAULT)
                .planId();
        long lowJob = runner.queue().jobsOfPlan(lowPlan).getFirst().id();
        runner.registerFactory(lowJob, context -> {
            started.add("low_work");
            return finishingTask("low_work");
        });
        long urgent = runner.enqueuePlan(
                "waits for netherite",
                new PlanDefinition(List.of(new PlanNode.Action(new QueuedStep(
                        "urgent_action",
                        new JsonObject(),
                        null,
                        List.of(new Guard.HasItem("minecraft:netherite_ingot", 1)),
                        GuardAction.WAIT,
                        FailurePolicy.ASK,
                        1,
                        List.of())))),
                new PlanOptions(5, FailurePolicy.ASK, 0),
                Map.of("urgent_action", (context, step) -> {
                    started.add("urgent_action");
                    return finishingTask("urgent_action");
                }));
        for (int i = 0; i < 5; i++) runner.tick();

        h.assertValueEqual(
                started,
                List.of("low_work"),
                "the blocked high-priority plan leaves unrelated work runnable: " + started);
        var urgentJobs = runner.queue().jobsOfPlan(urgent);
        h.assertValueEqual(urgentJobs.size(), 1, "the blocked action keeps its place: " + urgentJobs);
        h.assertValueEqual(
                urgentJobs.getFirst().state(),
                dev.friendai.execution.queue.JobState.PENDING,
                "the blocked action did not run: " + urgentJobs);
        h.succeed();
    }

    @GameTest(maxTicks = 60)
    public void aHighPriorityOrderTakesTheBodyWhenBusyWorkSettles(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        companion.inventory().addItem(new ItemStack(Items.DIAMOND, 1));
        List<String> started = new ArrayList<>();
        long lowPlan = runner.queue()
                .addPlan("busy", List.of(step("low_a"), step("low_b")), PlanOptions.DEFAULT)
                .planId();
        for (var job : runner.queue().jobsOfPlan(lowPlan)) {
            String tool = job.tool();
            runner.registerFactory(job.id(), context -> countingTask(tool, started));
        }
        for (int i = 0; i < 2; i++) runner.tick();
        h.assertValueEqual(started, List.of("low_a"), "the busy work holds the body");

        runner.enqueuePlan(
                "urgent",
                new PlanDefinition(List.of(new PlanNode.Conditional(
                        new Guard.HasItem("minecraft:diamond", 1), List.of(action("urgent_action")), List.of()))),
                new PlanOptions(5, FailurePolicy.ASK, 0),
                Map.of("urgent_action", (context, when) -> {
                    started.add("urgent_action");
                    return finishingTask("urgent_action");
                }));
        for (int i = 0; i < 8; i++) runner.tick();

        h.assertValueEqual(
                started,
                List.of("low_a", "urgent_action", "low_b"),
                "the order takes the body as soon as the busy work settles: " + started);
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aConditionalOnAnUnloadedSiteWaitsForEvidence(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        var site = companion.blockPosition().offset(2048, 0, 0);
        long plan = runner.enqueuePlan(
                "check a distant site",
                new PlanDefinition(List.of(new PlanNode.Conditional(
                        new Guard.Not(new Guard.BlockAt("minecraft:stone", site.getX(), site.getY(), site.getZ())),
                        List.of(action("then_action")),
                        List.of(action("else_action"))))),
                PlanOptions.DEFAULT,
                Map.of(
                        "then_action", recorder("then_action", started),
                        "else_action", recorder("else_action", started)));
        for (int i = 0; i < 20; i++) runner.tick();

        h.assertTrue(started.isEmpty(), "missing evidence runs no branch: " + started);
        h.assertTrue(runner.queue().jobsOfPlan(plan).isEmpty(), "no branch emitted a job");
        h.assertTrue(
                runner.queue()
                        .plan(plan)
                        .orElseThrow()
                        .cursor()
                        .selectedBranches()
                        .isEmpty(),
                "no branch was committed");
        String shown = QueueText.show(runner.queue(), new CompanionGuardFacts(companion), null, 10, false);
        h.assertTrue(shown.contains("cannot tell yet whether"), "the plan row names the missing fact: " + shown);
        h.assertTrue(shown.contains("minecraft:stone"), "and the fact it needs: " + shown);

        // A reload while the evidence is still unknown keeps the same plan, cursor and note.
        QueueSave.Decoded saved = new QueueSave.Decoded(runner.queue(), 0);
        CompanionEntity reloaded = h.spawn(FriendaiEntities.COMPANION, 5, 2, 5);
        ExecutionCoordinator again = new ExecutionCoordinator(reloaded, () -> 300, () -> false);
        again.installRestoredState(
                saved,
                Map.of(),
                Map.of(
                        plan,
                        Map.of(
                                "then_action", recorder("then_action", started),
                                "else_action", recorder("else_action", started))),
                null);
        again.wakeQueue();
        for (int i = 0; i < 5; i++) again.tick();
        h.assertTrue(started.isEmpty(), "the reloaded plan still runs no branch: " + started);
        h.assertTrue(again.queue().plan(plan).isPresent(), "the plan survives the reload");
        h.assertTrue(
                QueueText.show(again.queue(), new CompanionGuardFacts(reloaded), null, 10, false)
                        .contains("cannot tell yet whether"),
                "the reloaded plan row still names the missing fact");
        again.stop("test cleanup");
        runner.stop("test cleanup");
        h.succeed();
    }

    @GameTest(maxTicks = 200)
    public void aLoadedSiteChoosesItsBranchExactlyOnce(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        var withStone = companion.blockPosition().offset(1024, 0, 0);
        var withoutStone = companion.blockPosition().offset(-1024, 0, 0);
        long planA = runner.enqueuePlan(
                "site with stone",
                conditional(new Guard.Not(stoneAt(withStone))),
                PlanOptions.DEFAULT,
                Map.of("then_action", recorder("a_then", started), "else_action", recorder("a_else", started)));
        long planB = runner.enqueuePlan(
                "site without stone",
                conditional(new Guard.Not(stoneAt(withoutStone))),
                PlanOptions.DEFAULT,
                Map.of("then_action", recorder("b_then", started), "else_action", recorder("b_else", started)));
        for (int i = 0; i < 20; i++) runner.tick();
        h.assertTrue(started.isEmpty(), "nothing runs while both sites are unloaded: " + started);

        // Loading the sites supplies the evidence: one has the block (not(block_at) is false), one does not.
        h.getLevel().setBlock(withStone, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().getChunk(withoutStone.getX() >> 4, withoutStone.getZ() >> 4);
        for (int i = 0; i < 40; i++) runner.tick();

        h.assertValueEqual(
                started,
                List.of("a_else", "b_then"),
                "each site chooses its branch once the evidence exists: " + started);
        h.assertTrue(
                runner.queue().plan(planA).isEmpty()
                        && runner.queue().plan(planB).isEmpty(),
                "both finished");
        h.succeed();
    }

    private static Guard.BlockAt stoneAt(BlockPos pos) {
        return new Guard.BlockAt("minecraft:stone", pos.getX(), pos.getY(), pos.getZ());
    }

    private static PlanDefinition conditional(Guard condition) {
        return new PlanDefinition(List.of(
                new PlanNode.Conditional(condition, List.of(action("then_action")), List.of(action("else_action")))));
    }

    private static PlanStepFactory recorder(String name, List<String> started) {
        return (context, when) -> {
            started.add(name);
            return finishingTask(name);
        };
    }

    private static QueuedStep step(String tool) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of());
    }

    /** Records when the task starts and keeps the body for three ticks, so a dispatch can arrive while it runs. */
    private static Task countingTask(String name, List<String> started) {
        int[] ticks = {0};
        return new Task() {
            @Override
            public String type() {
                return name;
            }

            @Override
            public void start(TaskContext context) {
                started.add(name);
            }

            @Override
            public TaskStatus tick(TaskContext context) {
                return ++ticks[0] >= 3 ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return name;
            }

            @Override
            public String resultSummary() {
                return "done";
            }
        };
    }

    private static Task finishingTask(String name) {
        return new Task() {
            @Override
            public String type() {
                return name;
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                return TaskStatus.SUCCESS;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return name;
            }

            @Override
            public String resultSummary() {
                return "done";
            }
        };
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
