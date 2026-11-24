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
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.execution.ExecutionCoordinator;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

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
}
