package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.Guard;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.HoldKey;
import dev.friendai.execution.queue.Job;
import dev.friendai.execution.queue.JobState;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueueHold;
import dev.friendai.execution.queue.QueueText;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.task.queue.CompanionGuardFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** A prerequisite keeps its answer after the plan or goal that produced it leaves the active list. */
public final class TerminalPrerequisiteTests {
    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
    }

    private static Caller owner(ServerPlayer player) {
        return new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
    }

    private static QueuedStep step(String tool) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of());
    }

    private static QueuedStep guarded(String tool, Guard guard) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(guard), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of());
    }

    private static PlanDefinition waitPlan() {
        return new PlanDefinition(List.of(new PlanNode.Action(step("wait"))));
    }

    private static Task quickTask() {
        return new Task() {
            @Override
            public String type() {
                return "quick";
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
                return "quick";
            }

            @Override
            public String resultSummary() {
                return "done";
            }
        };
    }

    @GameTest(maxTicks = 200)
    public void aDependentPlanRunsAfterItsPredecessorIsArchived(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        long first = runner.enqueuePlan(
                "the predecessor",
                new PlanDefinition(List.of(new PlanNode.Action(step("first_step")))),
                PlanOptions.DEFAULT,
                Map.of("first_step", (context, step) -> {
                    started.add("first");
                    return quickTask();
                }));
        long second = runner.enqueuePlan(
                "the dependent",
                new PlanDefinition(List.of(new PlanNode.Action(guarded("second_step", new Guard.PlanDone(first))))),
                PlanOptions.DEFAULT,
                Map.of("second_step", (context, step) -> {
                    started.add("second");
                    return quickTask();
                }));

        for (int i = 0; i < 30; i++) runner.tick();

        h.assertTrue(runner.queue().plan(first).isEmpty(), "the predecessor finished and left the active list");
        h.assertTrue(runner.queue().archives().stream().anyMatch(a -> a.id() == first), "its outcome is archived");
        h.assertValueEqual(started, List.of("first", "second"), "the dependent ran once the answer existed");
        h.assertTrue(runner.queue().plan(second).isEmpty(), "the dependent finished too");
        h.succeed();
    }

    /** A task that reports one failure and never runs again, so an `on_failure=skip` step settles as skipped. */
    private static Task failingTask(String reason) {
        return new Task() {
            @Override
            public String type() {
                return "gather";
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                return TaskStatus.FAILED;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return "gather";
            }

            @Override
            public String resultSummary() {
                return reason;
            }
        };
    }

    @GameTest(maxTicks = 200)
    public void anOrderedStepRunsPastItsSkippedPrerequisiteWhileAnEffectGuardKeepsWaiting(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        QueuedStep failing = new QueuedStep(
                "gather", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.SKIP, 1, List.of());
        QueuedStep ordered = new QueuedStep(
                "tidy", new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.ASK, 1, List.of(1));
        QueuedStep needsOutput = new QueuedStep(
                "place",
                new JsonObject(),
                null,
                List.of(new Guard.HasItem("minecraft:oak_planks", 4)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of(1));
        var added =
                runner.queue().addPlan("stock the site", List.of(failing, ordered, needsOutput), PlanOptions.DEFAULT);
        long gather = added.jobIds().get(0),
                tidy = added.jobIds().get(1),
                place = added.jobIds().get(2);
        runner.registerFactory(gather, context -> failingTask("no oak in reach"));
        runner.registerFactory(tidy, context -> {
            started.add("tidy");
            return quickTask();
        });
        runner.registerFactory(place, context -> {
            started.add("place");
            return quickTask();
        });

        h.succeedWhen(() -> {
            runner.tick();
            Job skipped = runner.queue().job(gather).orElse(null);
            h.assertTrue(
                    skipped != null && skipped.state() == JobState.SKIPPED && started.contains("tidy"),
                    "waiting for the order-only step after its skipped prerequisite: " + started);
            h.assertValueEqual(skipped.result(), "no oak in reach", "the reason it was passed over is kept");
            h.assertTrue(!started.contains("place"), "the step needing the missing output did not run: " + started);
            h.assertTrue(runner.queue().plan(added.planId()).isPresent(), "the blocked step still waits");
            String shown = QueueText.show(runner.queue(), new CompanionGuardFacts(companion), null, 10, false);
            h.assertTrue(shown.contains("minecraft:oak_planks"), "the blocked step names its need: " + shown);
            h.assertFalse(shown.contains("decision"), "a skip is a decision, not an open question: " + shown);
        });
    }

    @GameTest(maxTicks = 200)
    public void aDependentPlanStillSeesAFailedPredecessor(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = companion.executionCoordinator();
        List<String> started = new ArrayList<>();
        long failed = runner.enqueuePlan(
                "doomed",
                new PlanDefinition(List.of(new PlanNode.Conditional(
                        new Guard.HasItem("minecraft:nether_star", 1),
                        List.of(new PlanNode.Action(step("never"))),
                        List.of(new PlanNode.Action(step("missing_tool")))))),
                PlanOptions.DEFAULT,
                Map.of("never", (context, step) -> quickTask()));
        long dependent = runner.enqueuePlan(
                "after the failure",
                new PlanDefinition(List.of(new PlanNode.Action(guarded("follow", new Guard.PlanDone(failed))))),
                PlanOptions.DEFAULT,
                Map.of("follow", (context, step) -> {
                    started.add("follow");
                    return quickTask();
                }));

        for (int i = 0; i < 40; i++) runner.tick();

        h.assertTrue(runner.queue().archives().stream().anyMatch(a -> a.id() == failed), "the failed plan is archived");
        h.assertTrue(runner.queue().plan(dependent).isPresent(), "the dependent is still queued");
        h.assertTrue(started.isEmpty(), "a failed predecessor does not satisfy the dependent: " + started);
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aFinishedGoalStillAnswersGoalMet(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ExecutionCoordinator runner = c.executionCoordinator();
        var board = GameTestSupport.runtime(h.getLevel().getServer()).goals().board(player.getUUID());

        h.assertFalse(
                call(c, owner(player), "set_goal", "{\"text\":\"bring logs\"}").error(), "the goal is set");
        long goalId = board.primary().orElseThrow().id();
        List<String> started = new ArrayList<>();
        long dependent = runner.enqueuePlan(
                "after the goal",
                new PlanDefinition(List.of(new PlanNode.Action(guarded("follow_up", new Guard.GoalMet(goalId))))),
                PlanOptions.DEFAULT,
                Map.of("follow_up", (context, step) -> {
                    started.add("follow_up");
                    return quickTask();
                }));

        for (int i = 0; i < 10; i++) runner.tick();
        h.assertTrue(started.isEmpty(), "an open goal does not satisfy the guard: " + started);

        h.assertFalse(
                call(c, owner(player), "finish_goal", "{\"id\":" + goalId + ",\"done\":true,\"reason\":\"ok\"}")
                        .error(),
                "the goal is finished");
        h.assertTrue(board.find(goalId).isEmpty(), "the goal left the active list");
        for (int i = 0; i < 20; i++) runner.tick();

        h.assertValueEqual(started, List.of("follow_up"), "the finished goal still answers the guard");
        h.assertTrue(runner.queue().plan(dependent).isEmpty(), "and the dependent completed");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void anAbandonedGoalDoesNotSatisfyGoalMet(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ExecutionCoordinator runner = c.executionCoordinator();
        var board = GameTestSupport.runtime(h.getLevel().getServer()).goals().board(player.getUUID());
        h.assertFalse(
                call(c, owner(player), "set_goal", "{\"text\":\"bring logs\"}").error(), "the goal is set");
        long goalId = board.primary().orElseThrow().id();
        List<String> started = new ArrayList<>();
        runner.enqueuePlan(
                "after the goal",
                new PlanDefinition(List.of(new PlanNode.Action(guarded("follow_up", new Guard.GoalMet(goalId))))),
                PlanOptions.DEFAULT,
                Map.of("follow_up", (context, step) -> {
                    started.add("follow_up");
                    return quickTask();
                }));

        call(c, owner(player), "finish_goal", "{\"id\":" + goalId + ",\"done\":false,\"reason\":\"dropped\"}");
        for (int i = 0; i < 20; i++) runner.tick();

        h.assertTrue(started.isEmpty(), "an abandoned goal never satisfies its prerequisite: " + started);
        h.succeed();
    }

    /** A step that stays active for a few ticks, so its job identity is observable between server ticks. */
    private static Task slowTask(int ticks) {
        return new Task() {
            private int elapsed;

            @Override
            public String type() {
                return "slow_step";
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                return ++elapsed >= ticks ? TaskStatus.SUCCESS : TaskStatus.RUNNING;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return "placing planks";
            }

            @Override
            public String resultSummary() {
                return "placed";
            }
        };
    }

    /** The remedy a blocked step waits for: it brings the guarded item and then succeeds. */
    private static Task deliveringPlanks(CompanionEntity companion) {
        return new Task() {
            @Override
            public String type() {
                return "obtain_item";
            }

            @Override
            public void start(TaskContext context) {
                companion.inventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
            }

            @Override
            public TaskStatus tick(TaskContext context) {
                return TaskStatus.SUCCESS;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return "gathering planks";
            }

            @Override
            public String resultSummary() {
                return "1 oak planks gathered";
            }
        };
    }

    @GameTest(maxTicks = 200)
    public void anAutomaticAcquisitionLetsTheBlockedStepRunOnceWithItsOwnIdentity(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        runner.setRepairFactory((principal, args) -> java.util.Optional.of(
                new ExecutionCoordinator.RepairPlan(context -> deliveringPlanks(context.companion()), false)));
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
        List<Long> started = new ArrayList<>();
        long planId = runner.enqueuePlan(
                "place planks",
                List.of(blocked),
                List.of(context -> {
                    started.add(1L);
                    return slowTask(5);
                }),
                PlanOptions.DEFAULT);
        long blockedJob = runner.queue().jobsOfPlan(planId).getFirst().id();
        long remedyId = runner.queue().jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                .mapToLong(Job::id)
                .findFirst()
                .orElse(0);
        long[] identity = {0};
        h.succeedWhen(() -> {
            if (identity[0] == 0 && !started.isEmpty())
                identity[0] = runner.activeJob().map(Job::id).orElse(0L);
            runner.tick();
            h.assertValueEqual(runner.idleRepairInserted(), 1, "exactly one automatic acquisition");
            h.assertValueEqual(started.size(), 1, "the original step ran");
            h.assertValueEqual(identity[0], blockedJob, "it kept the job and its cursor");
            h.assertTrue(remedyId > 0 && runner.queue().job(remedyId).isEmpty(), "the finished remedy leaves");
            h.assertTrue(runner.queue().plan(planId).isEmpty(), "the plan finished");
        });
    }

    /** A guarded step that waits for one item, as the queue sees it. */
    private static QueuedStep planksStep(java.util.UUID principal) {
        JsonObject args = new JsonObject();
        args.addProperty("block", "minecraft:oak_planks");
        return new QueuedStep(
                "place_block",
                args,
                null,
                List.of(new Guard.HasItem("minecraft:oak_planks", 4)),
                GuardAction.WAIT,
                FailurePolicy.ASK,
                1,
                List.of(),
                principal,
                false);
    }

    @GameTest(maxTicks = 200)
    public void aRevokedRequesterGetsNoGatheringWhileTheOwnersPlanStillDoes(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        var online = GameTestSupport.onlinePlayer(
                h, "repair-owner-" + java.util.UUID.randomUUID().toString().substring(0, 8), 2, 2, 2);
        ServerPlayer player = online.player();
        var server = h.getLevel().getServer();
        CompanionEntity companion = CompanionManager.get(server).summon(player);
        // The production session installs the repair seam on the companion's coordinator.
        GameTestSupport.runtime(server).sessions().currentPrompt(player.getUUID());
        ExecutionCoordinator runner = companion.executionCoordinator();
        java.util.UUID revoked = java.util.UUID.randomUUID();
        long guestPlan = runner.enqueuePlan(
                "the revoked plan", List.of(planksStep(revoked)), List.of(context -> quickTask()), PlanOptions.DEFAULT);
        long ownerPlan = runner.enqueuePlan(
                "the owner plan",
                List.of(planksStep(player.getUUID())),
                List.of(context -> quickTask()),
                PlanOptions.DEFAULT);

        for (int i = 0; i < 20; i++) runner.tick();

        List<Job> remedies = runner.queue().jobs().stream()
                .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                .toList();
        h.assertTrue(runner.idleRepairAttempts() > 0, "the idle repair check ran");
        h.assertValueEqual(runner.idleRepairInserted(), 1, "only the requester who may still use the tool is served");
        h.assertTrue(
                remedies.stream().noneMatch(job -> revoked.equals(job.principal())),
                "no gathering on behalf of the revoked player: " + remedies);
        h.assertTrue(
                remedies.stream().anyMatch(job -> player.getUUID().equals(job.principal())),
                "the owner's blocked step got its remedy: " + remedies);
        h.assertTrue(
                runner.queue().jobsOfPlan(guestPlan).stream().allMatch(job -> job.state() == JobState.PENDING),
                "the revoked plan's step stayed waiting untouched");
        h.assertTrue(!runner.queue().plan(ownerPlan).isEmpty(), "the owner plan continues");
        h.succeed();
    }

    /** A remedy that counts the ticks it actually runs, so a hold stopping it is visible from outside. */
    private static Task countingRemedy(int[] ticks) {
        return new Task() {
            @Override
            public String type() {
                return "remedy";
            }

            @Override
            public void start(TaskContext context) {}

            @Override
            public TaskStatus tick(TaskContext context) {
                ticks[0]++;
                return TaskStatus.RUNNING;
            }

            @Override
            public void stop(TaskContext context) {}

            @Override
            public String describe() {
                return "gathering planks";
            }

            @Override
            public String resultSummary() {
                return "gathering";
            }
        };
    }

    @GameTest(maxTicks = 200)
    public void aRemedyFollowsItsBlockedStepsHoldAndStopsWhenTheStepGoes(GameTestHelper h) {
        GameTestSupport.platform(h, 8);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        ExecutionCoordinator runner = new ExecutionCoordinator(companion, () -> 300, () -> false);
        int[] remedyTicks = {0};
        runner.setRepairFactory((principal, args) -> java.util.Optional.of(
                new ExecutionCoordinator.RepairPlan(context -> countingRemedy(remedyTicks), false)));
        long planId = runner.enqueuePlan(
                "place planks", List.of(planksStep(null)), List.of(context -> quickTask()), PlanOptions.DEFAULT);
        long blockedJob = runner.queue().jobsOfPlan(planId).getFirst().id();
        long remedyId = 0;
        for (int i = 0; i < 5 && remedyId == 0; i++) {
            runner.tick();
            remedyId = runner.queue().jobs().stream()
                    .filter(job -> job.planId() == 0 && job.tool().equals("obtain_item"))
                    .mapToLong(Job::id)
                    .findFirst()
                    .orElse(0);
        }
        h.assertTrue(remedyId > 0, "the blocked step got a remedy");
        for (int i = 0; i < 2; i++) runner.tick();
        h.assertTrue(remedyTicks[0] > 0, "the remedy is running: " + remedyTicks[0]);

        runner.queue().setHold(HoldKey.plan(planId), QueueHold.pause("paused by the owner"));
        runner.tick();
        int atHold = remedyTicks[0];

        h.assertTrue(runner.queue().job(remedyId).isPresent(), "the remedy is kept, not thrown away");
        for (int i = 0; i < 5; i++) runner.tick();
        h.assertValueEqual(remedyTicks[0], atHold, "its task froze at the hold boundary");
        h.assertValueEqual(runner.idleRepairInserted(), 1, "a held plan starts no second remedy");

        runner.queue().release(HoldKey.plan(planId));
        for (int i = 0; i < 3; i++) runner.tick();

        h.assertTrue(
                remedyTicks[0] > atHold,
                "the released remedy resumes rather than being recreated: " + remedyTicks[0] + " vs " + atHold);
        h.assertValueEqual(runner.idleRepairInserted(), 1, "and it is not inserted twice");

        runner.cancelPlan(planId, "cancelled by the owner");
        runner.tick();

        h.assertTrue(runner.queue().job(blockedJob).isEmpty(), "the cancelled step is archived with its plan");
        h.assertTrue(runner.queue().job(remedyId).isEmpty(), "the remedy left with the step that needed it");
        h.succeed();
    }
}
