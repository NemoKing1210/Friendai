package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.brain.BrainEvent;
import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class PlannerTests {
    private static final String FAILING_PLAN =
            "{\"goal\":\"tools\",\"steps\":[{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"}]}";

    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return FriendaiTools.create(() -> 32).bind(c).execute(call, caller);
    }

    /** A planner that always proposes one jump, and counts how often it was asked. */
    private static dev.friendai.agent.plan.Planner jumpPlanner(AtomicInteger asked) {
        return input -> {
            asked.incrementAndGet();
            JsonArray steps = new JsonArray();
            JsonObject step = new JsonObject();
            step.addProperty("tool", "jump");
            steps.add(step);
            return CompletableFuture.completedFuture(new PlanProposal(List.of(), steps, "hop first", "it hopped"));
        };
    }

    @GameTest(maxTicks = 400)
    public void goal_turn_plans_through_the_planner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        brains.setGatewayOverride(player.getUUID(), new CompanionControlTests.Script()
                .call("set_goal", "{\"text\":\"a house and some iron\"}")
                .text("[silent]"));
        CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.PlayerSaid(
                player.getUUID(), "Steve", "build a house and bring iron", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            h.assertTrue(asked.get() == 1, "the planner is asked once, asked=" + asked.get());
            var goal = state.goals().board(player.getUUID()).primary();
            h.assertTrue(goal.isPresent(), "the goal was set");
            h.assertTrue(goal.get().planId() != 0, "the planner's plan is linked to the goal");
            CompanionEntity c = CompanionManager.get(server).find(player.getUUID()).orElseThrow();
            h.assertTrue(c.taskRunner().queue().plan(goal.get().planId()).isPresent()
                    || !c.taskRunner().queue().archives().isEmpty(), "the plan is in the queue or already done");
        });
    }

    @GameTest(maxTicks = 600)
    public void recovery_replan_cancels_only_the_linked_plan(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        brains.setGatewayOverride(player.getUUID(), new CompanionControlTests.Script().text("[silent]").text("[silent]"));
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", FAILING_PLAN).error(), "failing plan queued and linked");
        long firstPlan = state.goals().board(player.getUUID()).primary().orElseThrow().planId();
        h.assertTrue(firstPlan != 0, "linked to the goal");
        h.succeedWhen(() -> {
            h.assertTrue(asked.get() >= 1, "the failed plan triggers a replan, asked=" + asked.get());
            var goal = state.goals().board(player.getUUID()).primary().orElseThrow();
            h.assertTrue(goal.planId() != firstPlan && goal.planId() != 0, "the goal now follows the new plan");
            h.assertTrue(c.taskRunner().queue().plan(firstPlan).isEmpty()
                    || c.taskRunner().queue().jobsOfPlan(firstPlan).stream()
                            .allMatch(j -> dev.friendai.queue.JobQueue.terminal(j)),
                    "the old plan no longer runs");
        });
    }
}
