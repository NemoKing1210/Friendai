package dev.friendai.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.plan.PlanProposal;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class PlannerTests {
    /**
     * A flat plan whose second step waits for the first: the failure leaves a pending step behind it, which is the
     * supported repair shape the planner may be consulted for.
     */
    private static final String FLAT_FAILING_PLAN = "{\"goal\":\"tools\",\"optimize\":true,\"steps\":["
            + "{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"},"
            + "{\"tool\":\"jump\",\"after\":[1]}]}";

    private static final String FAILING_PLAN_WITH_A_STEP_LEFT = "{\"goal\":\"iron\",\"steps\":["
            + "{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"},"
            + "{\"tool\":\"jump\"}]}";
    /** A plan whose first step asks the owner before stopping, so the second step stays pending for a repair. */
    private static final String BLOCKED_PLAN = "{\"goal\":\"tools\",\"steps\":["
            + "{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\","
            + "\"on_failure\":\"ask\"},"
            + "{\"tool\":\"jump\"}]}";

    private static final String IN_PLACE = "I could not work out a plan; I will stay here.";

    /** Records what the companion says, as the player would hear it. */
    private static final class Said implements dev.friendai.agent.turn.Speaker {
        final List<String> said = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void say(dev.friendai.foundation.text.UserMessage message) {}

        @Override
        public void thinking(boolean on) {}

        @Override
        public void error(dev.friendai.foundation.text.UserMessage message) {}
    }

    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
    }

    /** A planner that always proposes one jump, and counts how often it was asked. */
    private static dev.friendai.agent.plan.Planner jumpPlanner(AtomicInteger asked) {
        return jumpPlanner(asked, input -> {});
    }

    /** The same planner, also handing the input it received to the test. */
    private static dev.friendai.agent.plan.Planner jumpPlanner(
            AtomicInteger asked, java.util.function.Consumer<dev.friendai.agent.plan.PlannerInput> capture) {
        return input -> {
            asked.incrementAndGet();
            capture.accept(input);
            JsonArray steps = new JsonArray();
            JsonObject step = new JsonObject();
            step.addProperty("tool", "jump");
            steps.add(step);
            var action = new dev.friendai.execution.queue.QueuedStep(
                    "jump",
                    new JsonObject(),
                    null,
                    List.of(),
                    dev.friendai.execution.queue.GuardAction.WAIT,
                    null,
                    1,
                    List.of());
            return CompletableFuture.completedFuture(new PlanProposal(
                    new dev.friendai.execution.queue.PlanDefinition(
                            List.of(new dev.friendai.execution.queue.PlanNode.Action(action))),
                    steps,
                    "hop first",
                    "it hopped"));
        };
    }

    @GameTest(maxTicks = 400)
    public void goal_turn_plans_through_the_planner(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        brains.setGatewayOverride(
                player.getUUID(),
                new CompanionControlTests.Script()
                        .call("set_goal", "{\"text\":\"a house and some iron\"}")
                        .text("[silent]"));
        CompanionManager.get(server).summon(player);
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(
                        player.getUUID(), "Steve", "build a house and bring iron", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            h.assertTrue(asked.get() == 1, "the planner is asked once, asked=" + asked.get());
            var goal = state.goals().board(player.getUUID()).primary();
            h.assertTrue(goal.isPresent(), "the goal was set");
            CompanionEntity c =
                    CompanionManager.get(server).find(player.getUUID()).orElseThrow();
            // The planner queues the work beside the goal: a plan never links itself, so it cannot manufacture a
            // verified goal. The model links one itself when it means to.
            h.assertTrue(goal.get().planId() == 0, "the planner's plan stays off the goal");
            h.assertTrue(
                    c.executionCoordinator().queue().plan(goal.get().planId()).isPresent()
                            || !c.executionCoordinator().queue().archives().isEmpty(),
                    "the plan is in the queue or already done");
        });
    }

    /**
     * A plan repaired step by step is the supported shape: the flat plan keeps a pending step behind the failed one, so
     * the planner is asked once with that plan as its target and the stale-check fingerprint attached.
     */
    @GameTest(maxTicks = 700)
    public void a_flat_plan_repair_asks_the_planner_once_with_its_target(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        AtomicInteger asked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<dev.friendai.agent.plan.PlannerInput> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked, captured::set));
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            for (var message : messages) seen.add(message.text());
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", FLAT_FAILING_PLAN).error(), "flat plan queued and linked");
        long planId =
                state.goals().board(player.getUUID()).primary().orElseThrow().planId();
        h.assertTrue(planId != 0, "linked to the goal");
        // Give the failure, the repair and any wrongly triggered second repair time to happen.
        h.runAfterDelay(400, () -> {
            h.assertTrue(asked.get() == 1, "exactly one planner call, got " + asked.get());
            var input = captured.get();
            h.assertTrue(input != null, "the planner received an input");
            h.assertTrue(
                    input.targetPlanId() == planId,
                    "the input keeps the plan it repairs: " + input.targetPlanId() + " first=" + planId);
            h.assertFalse(input.targetFingerprint().isBlank(), "the stale-check fingerprint survives attachment");
            h.assertTrue(
                    input.toolDigest().contains("station: string[keep|take]"),
                    "the digest carries a closed enum, not prose: " + input.toolDigest());
            var goal = state.goals().board(player.getUUID()).primary().orElseThrow();
            h.assertTrue(goal.replans() == 1, "the replan is spent once, got " + goal.replans());
            h.assertTrue(goal.status() == dev.friendai.agent.goal.GoalStatus.ACTIVE, "the goal is not blocked");
            // The old plan is reported once, when it blocks; a repair of a step in place is silent.
            long reportsOfPlan = seen.stream()
                    .filter(line -> line.contains("plan #" + planId))
                    .count();
            h.assertTrue(
                    reportsOfPlan == 1,
                    "plan #" + planId + " must be reported exactly once, got " + reportsOfPlan + " " + seen);
            h.succeed();
        });
    }

    /**
     * A plan that stopped mid-tree for a decision is never rewritten behind its interpreter cursor. The planner is not
     * consulted at all for it: a proposal could not be applied, so the consultation would only spend a provider call,
     * and the plan stays where the owner can decide. The goal keeps the plan it linked, and the blocked report still
     * reaches the model turn.
     */
    @GameTest(maxTicks = 600)
    public void recovery_replan_leaves_a_started_plan_for_the_owners_decision(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        AtomicInteger asked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<dev.friendai.agent.plan.PlannerInput> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked, captured::set));
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            for (var message : messages) seen.add(message.text());
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", BLOCKED_PLAN).error(), "blocked plan queued and linked");
        long firstPlan =
                state.goals().board(player.getUUID()).primary().orElseThrow().planId();
        h.assertTrue(firstPlan != 0, "linked to the goal");
        h.succeedWhen(() -> {
            h.assertTrue(asked.get() == 0, "the unrepairable plan never reaches the planner, asked=" + asked.get());
            h.assertTrue(captured.get() == null, "no input was built for it");
            h.assertTrue(
                    seen.stream().anyMatch(line -> line.contains("plan #" + firstPlan + " needs a decision")),
                    "the blocked plan is still reported to the model: " + seen);
            var goal = state.goals().board(player.getUUID()).primary().orElseThrow();
            h.assertTrue(
                    goal.planId() == firstPlan,
                    "the goal keeps the plan it linked, got planId=" + goal.planId() + " first=" + firstPlan);
            var queue = c.executionCoordinator().queue();
            h.assertTrue(queue.plans().size() == 1, "no replacement plan was queued beside it");
            var plan = queue.plan(firstPlan).orElse(null);
            h.assertTrue(
                    plan != null
                            && queue.planStatus(firstPlan) == dev.friendai.execution.queue.PlanStatus.NEEDS_DECISION,
                    "the plan still waits for the owner: " + plan);
            h.assertTrue(
                    plan != null
                            && plan.cursor() != null
                            && plan.cursor().pendingAction() != null
                            && plan.cursor().pendingAction().tool().equals("craft_item")
                            && plan.definition().nodes().size() == 2,
                    "the plan keeps its steps and its place in them: " + plan);
        });
    }

    /**
     * A plan fails while its remaining work still waits for a decision and the planner cannot replace it: the
     * companion reports it from where it stands, retains the structured-plan continuation and does not start the idle
     * follow.
     */
    @GameTest(maxTicks = 1200)
    public void a_failed_plan_is_reported_in_place_and_keeps_the_queue(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 12, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        brains.setPlannerOverride(
                player.getUUID(),
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
            h.assertTrue(
                    ticksAfterTheLine[0] > 100,
                    "observing " + ticksAfterTheLine[0] + " ticks, task="
                            + c.executionCoordinator().currentToolName());
            h.assertTrue(said.said.size() == 1, "exactly one line for the failure, got " + said.said);
            double distance = c.position().distanceTo(player.position());
            h.assertTrue(
                    distance > distanceBefore - 1.0,
                    "the companion walked to the owner to say it: " + distanceBefore + " -> " + distance
                            + " blocks, running " + c.executionCoordinator().currentToolName());
            long planId = state.goals()
                    .board(player.getUUID())
                    .primary()
                    .map(dev.friendai.agent.goal.Goal::planId)
                    .orElse(0L);
            h.assertTrue(planId != 0, "the goal lost its plan");
            var queue = c.executionCoordinator().queue();
            var remainingPlan = queue.plan(planId).orElse(null);
            h.assertTrue(
                    remainingPlan != null
                            && queue.planStatus(planId) == dev.friendai.execution.queue.PlanStatus.NEEDS_DECISION,
                    "the failed plan is waiting for a decision: plan=" + remainingPlan);
            h.assertTrue(
                    remainingPlan != null
                            && remainingPlan.cursor() != null
                            && !remainingPlan.cursor().stack().isEmpty()
                            && remainingPlan.cursor().pendingAction() != null
                            && remainingPlan.cursor().pendingAction().tool().equals("craft_item")
                            && remainingPlan.cursor().stack().getLast().nextIndex() == 1
                            && remainingPlan.definition().nodes().size() == 2
                            && remainingPlan.definition().nodes().get(1)
                                    instanceof dev.friendai.execution.queue.PlanNode.Action action
                            && action.step().tool().equals("jump"),
                    "the structured plan continuation for jump was lost: " + remainingPlan);
        });
    }
}
