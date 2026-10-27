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
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.ToolSpec;
import dev.friendai.llm.model.Usage;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class PlannerTests {
    private static final String FAILING_PLAN =
            "{\"goal\":\"tools\",\"steps\":[{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"}]}";
    private static final String FAILING_PLAN_WITH_A_STEP_LEFT =
            "{\"goal\":\"iron\",\"steps\":["
                    + "{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"},"
                    + "{\"tool\":\"jump\"}]}";
    private static final String IN_PLACE = "I could not work out a plan; I will stay here.";

    /** Records what the companion says, as the player would hear it. */
    private static final class Said implements dev.friendai.brain.Speaker {
        final List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void thinking(boolean on) {}

        @Override
        public void error(dev.friendai.text.UserMessage message) {}
    }

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

    @GameTest(maxTicks = 700)
    public void replacing_a_plan_spends_exactly_one_replan_and_asks_once(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        AtomicInteger requests = new AtomicInteger();
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            requests.incrementAndGet();
            seen.add(messages.get(messages.size() - 1).text());
            return CompletableFuture.completedFuture(new dev.friendai.llm.model.LlmResponse(
                    "[silent]", List.of(), dev.friendai.llm.model.StopReason.END, new dev.friendai.llm.model.Usage(0, 0)));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", FAILING_PLAN).error(), "failing plan queued and linked");
        // Give the failure, the replan and any wrongly triggered second replan time to happen.
        h.runAfterDelay(400, () -> {
            h.assertTrue(asked.get() == 1, "exactly one planner call, got " + asked.get());
            var goal = state.goals().board(player.getUUID()).primary().orElseThrow();
            h.assertTrue(goal.replans() == 1, "the replan is spent once, got " + goal.replans());
            h.assertTrue(goal.status() == dev.friendai.agent.goal.GoalStatus.ACTIVE, "the goal is not blocked");
            // The old plan is reported once, when it blocks; cancelling it for the replacement is silent.
            long reportsOfOldPlan = seen.stream().filter(line -> line.contains("plan #1")).count();
            h.assertTrue(reportsOfOldPlan == 1, "plan #1 must be reported exactly once, got " + reportsOfOldPlan + " " + seen);
            h.succeed();
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

    /**
     * A plan fails while its remaining work still waits for a decision and the planner cannot replace it: the
     * companion reports it from where it stands, keeps the queue and does not start the idle follow.
     */
    @GameTest(maxTicks = 1200)
    public void a_failed_plan_is_reported_in_place_and_keeps_the_queue(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 12, 2, 2);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        BrainService brains = state.brains();
        brains.setPlannerOverride(player.getUUID(),
                input -> CompletableFuture.failedFuture(new IllegalStateException("planner is down")));
        CompanionEntity c = CompanionManager.get(server).summon(player);
        BlockPos start = h.absolutePos(new BlockPos(2, 2, 2));
        c.snapTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, 0, 0);
        double distanceBefore = c.position().distanceTo(player.position());
        Said said = new Said();
        brains.setSpeakerWrapper(player.getUUID(), real -> said);
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        List<String> setupErrors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger requests = new AtomicInteger();
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            if (requests.incrementAndGet() == 1) {
                // Create the work only once the brain exists, so its task listener is already installed.
                server.execute(() -> {
                    ToolResult goal = call(c, owner, "set_goal", "{\"text\":\"mine iron\"}");
                    if (goal.error()) setupErrors.add(goal.content());
                    ToolResult plan = call(c, owner, "queue_add", FAILING_PLAN_WITH_A_STEP_LEFT);
                    if (plan.error()) setupErrors.add(plan.content());
                });
                return CompletableFuture.completedFuture(
                        new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
            }
            return CompletableFuture.completedFuture(
                    new LlmResponse(IN_PLACE, List.of(), StopReason.END, new Usage(0, 0)));
        });
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        int[] ticksAfterTheLine = {-1};
        h.succeedWhen(() -> {
            h.assertTrue(setupErrors.isEmpty(), "the test could not queue its work: " + setupErrors);
            h.assertTrue(said.said.contains(IN_PLACE), "the failure was not reported in chat: " + said.said);
            // Give a follow that started after the failure a full second of game time to show itself.
            if (ticksAfterTheLine[0] < 0) {
                ticksAfterTheLine[0] = 0;
                h.assertTrue(false, "the line arrived; observing the next ticks");
            }
            ticksAfterTheLine[0]++;
            h.assertTrue(ticksAfterTheLine[0] > 100,
                    "observing " + ticksAfterTheLine[0] + " ticks, task=" + c.taskRunner().currentToolName());
            h.assertTrue(said.said.size() == 1, "exactly one line for the failure, got " + said.said);
            double distance = c.position().distanceTo(player.position());
            h.assertTrue(distance > distanceBefore - 1.0,
                    "the companion walked to the owner to say it: " + distanceBefore + " -> " + distance
                            + " blocks, running " + c.taskRunner().currentToolName());
            long planId = state.goals().board(player.getUUID()).primary()
                    .map(dev.friendai.agent.goal.Goal::planId).orElse(0L);
            h.assertTrue(planId != 0, "the goal lost its plan");
            h.assertTrue(c.taskRunner().queue().jobsOfPlan(planId).stream()
                            .anyMatch(job -> job.state() == dev.friendai.queue.JobState.PENDING),
                    "the remaining step was dropped from the queue");
        });
    }
}
