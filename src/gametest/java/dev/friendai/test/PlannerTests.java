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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
            return CompletableFuture.completedFuture(jumpProposal());
        };
    }

    /** One jump step, the proposal every planner in this class answers with. */
    private static PlanProposal jumpProposal() {
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
        return new PlanProposal(
                new dev.friendai.execution.queue.PlanDefinition(
                        List.of(new dev.friendai.execution.queue.PlanNode.Action(action))),
                steps,
                "hop first",
                "it hopped");
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
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
            // This proposal serves the owner order, which had no persisted goal when the planner started.
            h.assertTrue(goal.get().planId() == 0, "the planner's plan stays off the goal");
            h.assertTrue(
                    c.executionCoordinator().queue().plan(goal.get().planId()).isPresent()
                            || !c.executionCoordinator().queue().archives().isEmpty(),
                    "the plan is in the queue or already done");
        });
    }

    /**
     * The owner asks to see a plan first. The planner's valid proposal reaches the owner as a proposal and leaves the
     * queue, the goals and the world untouched; the next line approves it, and only then is it queued.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
    public void a_requested_proposal_starts_nothing_until_the_owner_approves_it(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        java.util.List<String> prompts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            prompts.add(system);
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        long[] ticks = {0};
        String[] phase = {"propose"};
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(
                        player.getUUID(),
                        "Steve",
                        "Make a plan to build a house. Do nothing yet.",
                        BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            ticks[0]++;
            var queue = c.executionCoordinator().queue();
            var board = state.goals().board(player.getUUID());
            if (phase[0].equals("propose")) {
                h.assertTrue(asked.get() == 1, "the planner is asked once, asked=" + asked.get());
                h.assertTrue(
                        prompts.stream().anyMatch(system -> system.contains("NOT queued")),
                        "the owner is shown a proposal, not a queued plan: " + prompts);
                h.assertTrue(queue.plans().isEmpty() && queue.jobs().isEmpty(), "nothing was queued");
                h.assertTrue(queue.archives().isEmpty(), "nothing ran and finished");
                h.assertTrue(board.primary().isEmpty(), "no goal was set");
                if (ticks[0] < 20) {
                    // Let the proposal turn end before approving it, so the approval is its own turn.
                    h.assertTrue(false, "watching the proposal turn settle");
                }
                phase[0] = "approve";
                brains.post(
                        player.getUUID(),
                        new BrainEvent.PlayerSaid(player.getUUID(), "Steve", "go ahead", BrainEvent.Source.CHAT));
                return;
            }
            var plan = queue.plans().stream().findFirst().orElse(null);
            h.assertTrue(
                    plan != null || !queue.archives().isEmpty(),
                    "the approved plan is queued or already ran: plans=" + queue.plans());
        });
    }

    /**
     * A first line that points at something the conversation never established ("the second version", "over
     * there") is answered with a question, not planned: no planner request is spent, nothing is queued and the
     * world is untouched. Once the transcript holds a delivered exchange, a line of the same shape is planned with
     * that dialogue as the evidence that resolves it.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
    public void an_order_whose_reference_nothing_grounds_is_questioned_not_planned(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(player.getUUID(), jumpPlanner(asked));
        java.util.List<String> prompts = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        brains.setGatewayOverride(player.getUUID(), (system, messages, tools) -> {
            prompts.add(system);
            return CompletableFuture.completedFuture(
                    new LlmResponse("Which one do you mean?", List.of(), StopReason.END, new Usage(0, 0)));
        });
        CompanionEntity c = CompanionManager.get(server).summon(player);
        var queue = c.executionCoordinator().queue();
        int[] ticks = {0};
        String[] phase = {"cold"};
        brains.post(
                player.getUUID(),
                new BrainEvent.PlayerSaid(
                        player.getUUID(),
                        "Steve",
                        "build the second version over there and bring me 16 oak planks",
                        BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            ticks[0]++;
            if (phase[0].equals("cold")) {
                h.assertTrue(
                        prompts.stream().anyMatch(system -> system.contains("Ask the player one short question")),
                        "the refusal reaches the model as a question to ask: " + prompts);
                h.assertTrue(asked.get() == 0, "no planner request was spent, asked=" + asked.get());
                h.assertTrue(queue.plans().isEmpty() && queue.jobs().isEmpty(), "nothing was queued");
                h.assertTrue(queue.archives().isEmpty(), "nothing ran");
                h.assertTrue(state.goals().board(player.getUUID()).primary().isEmpty(), "no goal was set");
                var session = brains.find(player.getUUID()).orElse(null);
                if (session != null && session.isThinking()) {
                    h.assertTrue(false, "waiting for the question turn to settle");
                }
                phase[0] = "warm";
                brains.post(
                        player.getUUID(),
                        new BrainEvent.PlayerSaid(
                                player.getUUID(),
                                "Steve",
                                "the second one, same spot, and bring 8 oak planks",
                                BrainEvent.Source.CHAT));
                return;
            }
            h.assertTrue(asked.get() == 1, "the delivered dialogue now grounds the reference, asked=" + asked.get());
            h.assertTrue(
                    !queue.plans().isEmpty() || !queue.archives().isEmpty(),
                    "the intended plan was queued: plans=" + queue.plans());
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void active_goal_planner_links_the_exact_goal(GameTestHelper h) {
        activeGoalPlan(h, false);
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void active_goal_planner_refuses_a_replaced_goal(GameTestHelper h) {
        activeGoalPlan(h, true);
    }

    /** The owner finishes the goal's only criterion while the provider is still answering. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void a_delayed_goal_plan_is_refused_when_its_work_is_done(GameTestHelper h) {
        delayedGoalPlan(h, List.of("carry bread"), c -> c.inventory().addItem(new ItemStack(Items.BREAD, 1)), false);
    }

    /** The owner does half of the drafted work: the plan was drawn for both criteria, so it goes back for a new one. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void a_delayed_goal_plan_is_refused_when_some_of_its_work_is_done(GameTestHelper h) {
        delayedGoalPlan(
                h,
                List.of("carry bread", "carry a stick"),
                c -> c.inventory().addItem(new ItemStack(Items.BREAD, 1)),
                false);
    }

    /** A change that touches nothing the goal checks leaves the drafted plan usable. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 400)
    public void an_unrelated_change_keeps_a_delayed_goal_plan_usable(GameTestHelper h) {
        delayedGoalPlan(h, List.of("carry bread"), c -> c.inventory().addItem(new ItemStack(Items.DIRT, 1)), true);
    }

    /**
     * Plans a goal whose criteria name carried items, with the provider answering after the owner's change. Whatever
     * the change did to the criteria, the queue and the goal link must agree with whether the proposal was still
     * current when it arrived.
     */
    private static void delayedGoalPlan(
            GameTestHelper h,
            List<String> criteriaTexts,
            java.util.function.Consumer<CompanionEntity> whileWaiting,
            boolean planIsQueued) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        var state = GameTestSupport.runtime(server);
        var owner = player.getUUID();
        var board = state.goals().board(owner);
        List<dev.friendai.agent.goal.Criterion> criteria = criteriaTexts.stream()
                .map(text -> new dev.friendai.agent.goal.Criterion(
                        text,
                        java.util.Optional.of(new dev.friendai.execution.queue.Guard.HasItem(
                                text.equals("carry bread") ? "minecraft:bread" : "minecraft:stick", 1))))
                .toList();
        var goal = board.set("Bring me things", criteria, false, h.getLevel().getGameTime())
                .value();
        var companion = CompanionManager.get(server).summon(player);
        var replied = new AtomicInteger();
        state.sessions().setPlannerOverride(owner, input -> {
            var pending = new CompletableFuture<PlanProposal>();
            h.runAfterDelay(1, () -> {
                whileWaiting.accept(companion);
                pending.complete(jumpProposal());
            });
            return pending;
        });
        state.sessions().setGatewayOverride(owner, (system, messages, tools) -> {
            replied.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        state.sessions().post(owner, new BrainEvent.GoalTick(goal.text(), criteriaTexts));
        h.succeedWhen(() -> {
            h.assertTrue(replied.get() > 0, "the turn settled");
            var queue = companion.executionCoordinator().queue();
            long planId = board.primary().orElseThrow().planId();
            if (planIsQueued) {
                h.assertTrue(
                        planId > 0
                                && (queue.plan(planId).isPresent()
                                        || queue.archives().stream().anyMatch(a -> a.id() == planId)),
                        "the current plan is linked: " + planId);
            } else {
                h.assertTrue(planId == 0, "the goal stays unlinked: " + planId);
                h.assertTrue(
                        queue.jobs().isEmpty() && queue.plans().isEmpty(),
                        "stale work never starts: jobs=" + queue.jobs() + " plans=" + queue.plans());
            }
        });
    }

    private static void activeGoalPlan(GameTestHelper h, boolean replace) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        var state = GameTestSupport.runtime(server);
        var owner = player.getUUID();
        var board = state.goals().board(owner);
        var original = board.set("Jump once", List.of(), false, h.getLevel().getGameTime())
                .value();
        var pending = new CompletableFuture<PlanProposal>();
        var observed = new AtomicInteger();
        var replied = new AtomicInteger();
        var planner = jumpPlanner(new AtomicInteger());
        state.sessions().setPlannerOverride(owner, input -> {
            observed.incrementAndGet();
            h.runAfterDelay(1, () -> {
                if (replace) {
                    board.finish(original.id(), false, "changed order");
                    board.set("Jump once", List.of(), false, h.getLevel().getGameTime());
                }
                planner.propose(input).whenComplete((plan, failure) -> {
                    if (failure == null) pending.complete(plan);
                    else pending.completeExceptionally(failure);
                });
            });
            return pending;
        });
        state.sessions().setGatewayOverride(owner, (system, messages, tools) -> {
            replied.incrementAndGet();
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        });
        var companion = CompanionManager.get(server).summon(player);
        state.sessions().post(owner, new BrainEvent.GoalTick(original.text(), List.of("jump once")));
        h.succeedWhen(() -> {
            h.assertTrue(observed.get() == 1 && replied.get() > 0, "the submitted proposal reached disposition");
            var current = board.primary().orElseThrow();
            var queue = companion.executionCoordinator().queue();
            if (replace) {
                h.assertTrue(current.id() != original.id() && current.planId() == 0, "replacement goal stays unlinked");
                h.assertTrue(queue.archives().isEmpty() && queue.jobs().isEmpty(), "stale work never starts");
            } else {
                h.assertTrue(current.id() == original.id() && current.planId() > 0, "planner links the exact goal");
                h.assertTrue(
                        queue.archives().stream().anyMatch(a -> a.id() == current.planId() && a.success()),
                        "the linked plan actually succeeds");
            }
        });
    }

    /**
     * A receipt proves the goal and plan that earned it. The actual planner requests show a delivery to the same
     * goal's new plan and to the repair of that plan, show nothing to an owner order, another plan's repair or a
     * guest, and recheck a stock receipt against what the companion really carries.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void a_planner_input_offers_only_the_receipts_of_its_own_work(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer ownerPlayer = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionEntity c = CompanionManager.get(server).summon(ownerPlayer);
        c.inventory().clearContent();
        Caller owner = new Caller(ownerPlayer.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"make sticks\"}").error(), "goal set");
        long goalId = state.goals()
                .board(ownerPlayer.getUUID())
                .primary()
                .orElseThrow()
                .id();
        h.assertFalse(
                call(c, owner, "queue_add", GameTestToolRegistry.forPrimaryGoal(c, FLAT_FAILING_PLAN))
                        .error(),
                "the goal's plan is queued");
        long goalPlanId = state.goals()
                .board(ownerPlayer.getUUID())
                .primary()
                .orElseThrow()
                .planId();
        h.assertFalse(call(c, owner, "queue_add", FLAT_FAILING_PLAN).error(), "an unowned plan is queued");
        long otherPlanId = c.executionCoordinator().queue().plans().stream()
                .mapToLong(dev.friendai.execution.queue.PlanRecord::id)
                .filter(id -> id != goalPlanId)
                .max()
                .orElseThrow();

        long now = server.getTickCount();
        List<dev.friendai.agent.verify.VerifiedEvidence> receipts = new java.util.ArrayList<>(List.of(
                new dev.friendai.agent.verify.VerifiedEvidence(
                        dev.friendai.agent.verify.VerifiedEvidence.Kind.DELIVERED,
                        "minecraft:stick",
                        4,
                        4,
                        goalId,
                        goalPlanId,
                        now),
                new dev.friendai.agent.verify.VerifiedEvidence(
                        dev.friendai.agent.verify.VerifiedEvidence.Kind.CARRIED,
                        "minecraft:stick",
                        4,
                        4,
                        goalId,
                        goalPlanId,
                        now)));
        java.util.function.ToIntFunction<String> held = itemId -> {
            int count = 0;
            for (int slot = 0; slot < c.inventory().getContainerSize(); slot++) {
                ItemStack stack = c.inventory().getItem(slot);
                if (!stack.isEmpty()
                        && net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem())
                                .toString()
                                .equals(itemId)) {
                    count += stack.getCount();
                }
            }
            return count;
        };
        var factory = new dev.friendai.server.agent.planner.PlannerInputFactory(
                c,
                () -> new dev.friendai.server.agent.perception.PerceptionContext(
                        state.resourceCandidates(), state.settings(), state.freeTime()),
                java.util.Map.of(),
                List::of,
                () -> state.goals().board(ownerPlayer.getUUID()),
                () -> state.config(),
                (requestedGoal, requestedPlan, at) -> dev.friendai.agent.verify.VerifiedEvidence.forObjective(
                        receipts, requestedGoal, requestedPlan, at, held::applyAsInt),
                () -> state.freeTime());

        var goalInput = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.ActiveGoal(goalId, List.of("craft sticks")), owner, "")
                .orElseThrow();
        h.assertTrue(
                goalInput.verified().contains("handed_over 4/4 minecraft:stick"),
                "the goal's own new plan cites its verified craft: " + goalInput.verified());
        h.assertFalse(
                goalInput.verified().stream().anyMatch(line -> line.startsWith("carried")),
                "a stock receipt nothing backs is not offered: " + goalInput.verified());
        var repairInput = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.TroubledPlan(goalPlanId, "boom"), owner, "")
                .orElseThrow();
        h.assertTrue(
                repairInput.verified().contains("handed_over 4/4 minecraft:stick"),
                "a repair of the same plan keeps its own receipt: " + repairInput.verified());
        var orderInput = factory.build(new dev.friendai.agent.plan.PlanSubject.OwnerOrder("dig a hole"), owner, "")
                .orElseThrow();
        h.assertTrue(orderInput.verified().isEmpty(), "an owner order inherited a receipt: " + orderInput.verified());
        var otherRepair = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.TroubledPlan(otherPlanId, "boom"), owner, "")
                .orElseThrow();
        h.assertTrue(
                otherRepair.verified().isEmpty(),
                "another plan inherited the goal's receipt: " + otherRepair.verified());
        var guestInput = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.ActiveGoal(goalId, List.of("craft sticks")),
                        new Caller(ownerPlayer.getUUID(), "Alex", AccessLevel.GUEST),
                        "")
                .orElseThrow();
        h.assertTrue(
                guestInput.verified().isEmpty(), "a guest inherited the owner's receipt: " + guestInput.verified());
        var trustedInput = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.ActiveGoal(goalId, List.of("craft sticks")),
                        new Caller(ownerPlayer.getUUID(), "Alex", AccessLevel.TRUSTED),
                        "")
                .orElseThrow();
        h.assertTrue(
                trustedInput.verified().contains("handed_over 4/4 minecraft:stick"),
                "a trusted caller may see what the companion was asked to do: " + trustedInput.verified());
        // The stock receipt becomes proof only while the companion really carries it, and is re-rendered with what
        // remains once the sticks are partly used.
        c.inventory().addItem(new ItemStack(Items.STICK, 4));
        var stocked = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.ActiveGoal(goalId, List.of("craft sticks")), owner, "")
                .orElseThrow();
        h.assertTrue(
                stocked.verified().contains("carried 4/4 minecraft:stick"),
                "a held receipt is offered with the live count: " + stocked.verified());
        c.inventory().setItem(0, new ItemStack(Items.STICK, 1));
        var used = factory.build(
                        new dev.friendai.agent.plan.PlanSubject.ActiveGoal(goalId, List.of("craft sticks")), owner, "")
                .orElseThrow();
        h.assertTrue(
                used.verified().contains("carried 1/4 minecraft:stick"),
                "a partly used receipt offers what remains: " + used.verified());
        h.succeed();
    }

    /**
     * A plan repaired step by step is the supported shape: the flat plan keeps a pending step behind the failed one, so
     * the planner is asked once with that plan as its target and the stale-check fingerprint attached.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 700)
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
        h.assertFalse(
                call(c, owner, "queue_add", GameTestToolRegistry.forPrimaryGoal(c, FLAT_FAILING_PLAN))
                        .error(),
                "flat plan queued and linked");
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
            // The failed step is what the proposal replaces; patching the pending step behind it would leave the
            // plan blocked on the same failure.
            h.assertTrue(
                    c.executionCoordinator().queue().jobsOfPlan(planId).stream()
                            .noneMatch(job -> job.state() == dev.friendai.execution.queue.JobState.FAILED),
                    "the repair must replace the failed step, not its pending successor: "
                            + c.executionCoordinator().queue().jobsOfPlan(planId));
            // The blocked plan reaches the model once the consultation is spent; a repair of a step in place is
            // silent. A later request replays the same user line from history, so the check is that the report
            // exists, not how many requests quote it.
            h.assertTrue(
                    seen.stream().anyMatch(line -> line.contains("plan #" + planId + " needs a decision")),
                    "the blocked plan must reach the model: " + seen);
            h.succeed();
        });
    }

    /**
     * A trusted player's failed plan is repaired as that player: the consultation carries their tool scope and none
     * of the owner's stored address or description, and the step the repair queues still runs as the trusted player.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 800)
    public void a_trusted_players_repair_uses_their_own_instructions(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer ownerPlayer = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        ServerPlayer trustedPlayer = GameTestSupport.mockPlayerAt(h, 6, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        java.util.UUID ownerId = ownerPlayer.getUUID();
        java.util.UUID trustedId = trustedPlayer.getUUID();
        state.memory().book(ownerId).setTrusted(trustedId, "Alex", true, System.currentTimeMillis());
        state.profiles()
                .save(
                        ownerId,
                        new dev.friendai.features.memory.CompanionProfile(
                                "Mira",
                                new dev.friendai.features.memory.CompanionDescription(
                                        "", "Owner-only detail for the planner"),
                                new dev.friendai.features.memory.OwnerProfile("auto", "Captain")));
        AtomicInteger asked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<dev.friendai.agent.plan.PlannerInput> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        brains.setPlannerOverride(ownerId, jumpPlanner(asked, captured::set));
        brains.setGatewayOverride(
                ownerId,
                (system, messages, tools) -> CompletableFuture.completedFuture(
                        new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0))));
        CompanionEntity c = CompanionManager.get(server).summon(ownerPlayer);
        brains.post(ownerId, new BrainEvent.WorldNotice("ready"));
        Caller trusted = new Caller(trustedId, "Alex", AccessLevel.TRUSTED);
        h.assertFalse(call(c, trusted, "queue_add", FLAT_FAILING_PLAN).error(), "the trusted plan was queued");
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> repairedAs =
                new java.util.concurrent.atomic.AtomicReference<>();
        h.succeedWhen(() -> {
            var input = captured.get();
            h.assertTrue(input != null, "the trusted player's plan reached the planner");
            h.assertTrue(input.targetPlanId() > 0, "the consultation repairs that plan");
            String instructions = input.instructions().stream()
                    .map(section -> section.render(null).orElse(""))
                    .collect(java.util.stream.Collectors.joining("\n"));
            h.assertTrue(
                    !instructions.contains("Captain"), "the owner's address reached a trusted repair: " + instructions);
            h.assertTrue(
                    !instructions.contains("Owner-only detail"),
                    "the owner's description reached a trusted repair: " + instructions);
            c.executionCoordinator().queue().jobsOfPlan(input.targetPlanId()).stream()
                    .filter(job -> job.tool().equals("jump"))
                    .findFirst()
                    .ifPresent(job -> repairedAs.set(job.principal()));
            h.assertTrue(repairedAs.get() != null, "the repair was applied");
            h.assertValueEqual(repairedAs.get(), trustedId, "the repaired step runs as the trusted player");
            h.assertTrue(asked.get() == 1, "exactly one planner call, got " + asked.get());
        });
    }

    /**
     * Trust withdrawn while the planner is still answering drops the proposal: the plan is rechecked against live
     * owner and trust data at apply time, so no repaired step is queued for a player who may no longer run it.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 800)
    public void a_repair_is_dropped_when_trust_is_revoked_while_the_planner_answers(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer ownerPlayer = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        ServerPlayer trustedPlayer = GameTestSupport.mockPlayerAt(h, 6, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionSessions brains = state.sessions();
        java.util.UUID ownerId = ownerPlayer.getUUID();
        java.util.UUID trustedId = trustedPlayer.getUUID();
        state.memory().book(ownerId).setTrusted(trustedId, "Alex", true, System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean(false);
        AtomicInteger asked = new AtomicInteger();
        brains.setPlannerOverride(ownerId, input -> {
            asked.incrementAndGet();
            var answer = new CompletableFuture<PlanProposal>();
            Thread.ofPlatform().daemon().start(() -> {
                while (!release.get()) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
                answer.complete(jumpProposal());
            });
            return answer;
        });
        brains.setGatewayOverride(
                ownerId,
                (system, messages, tools) -> CompletableFuture.completedFuture(
                        new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0))));
        CompanionEntity c = CompanionManager.get(server).summon(ownerPlayer);
        brains.post(ownerId, new BrainEvent.WorldNotice("ready"));
        Caller trusted = new Caller(trustedId, "Alex", AccessLevel.TRUSTED);
        h.assertFalse(call(c, trusted, "queue_add", FLAT_FAILING_PLAN).error(), "the trusted plan was queued");
        String[] phase = {"consult"};
        int[] ticks = {0};
        long[] planId = {0};
        h.succeedWhen(() -> {
            if (phase[0].equals("consult")) {
                h.assertTrue(asked.get() == 1, "the plan reached the planner, asked=" + asked.get());
                planId[0] = c.executionCoordinator().queue().plans().stream()
                        .mapToLong(dev.friendai.execution.queue.PlanRecord::id)
                        .findFirst()
                        .orElse(0L);
                h.assertTrue(planId[0] > 0, "the plan is still queued while the planner answers");
                state.memory().book(ownerId).setTrusted(trustedId, "Alex", false, System.currentTimeMillis());
                brains.authorityChanged(ownerId);
                release.set(true);
                phase[0] = "refused";
                h.assertTrue(false, "trust revoked while the planner was answering");
                return;
            }
            ticks[0]++;
            var session = brains.find(ownerId).orElse(null);
            if (ticks[0] < 40 || (session != null && session.isThinking())) {
                h.assertTrue(false, "waiting for the consultation to be applied or dropped");
            }
            var jobs = c.executionCoordinator().queue().jobsOfPlan(planId[0]);
            // A repair would replace the failed step; the plan keeping that same step is the proof nothing was
            // applied on behalf of a player whose trust is gone.
            h.assertTrue(
                    jobs.stream()
                            .anyMatch(job -> job.tool().equals("craft_item")
                                    && job.state() == dev.friendai.execution.queue.JobState.FAILED),
                    "the revoked repair was not applied: " + jobs);
            h.assertTrue(
                    jobs.stream().noneMatch(job -> job.state() == dev.friendai.execution.queue.JobState.DONE),
                    "no step ran for the revoked player: " + jobs);
        });
    }

    /**
     * A plan that stopped mid-tree for a decision is never rewritten behind its interpreter cursor. The planner is not
     * consulted at all for it: a proposal could not be applied, so the consultation would only spend a provider call,
     * and the plan stays where the owner can decide. The goal keeps the plan it linked, and the blocked report still
     * reaches the model turn.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 600)
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
        h.assertFalse(
                call(c, owner, "queue_add", GameTestToolRegistry.forPrimaryGoal(c, BLOCKED_PLAN))
                        .error(),
                "blocked plan queued and linked");
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
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 1200)
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
                    ToolResult plan = call(
                            c,
                            owner,
                            "queue_add",
                            GameTestToolRegistry.forPrimaryGoal(c, FAILING_PLAN_WITH_A_STEP_LEFT));
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
