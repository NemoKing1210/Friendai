package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.queue.FailurePolicy;
import dev.friendai.execution.queue.GuardAction;
import dev.friendai.execution.queue.PlanDefinition;
import dev.friendai.execution.queue.PlanNode;
import dev.friendai.execution.queue.PlanOptions;
import dev.friendai.execution.queue.QueuedStep;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.protocol.control.ControlRequest;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class GoalTests {
    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
    }

    private static ToolCall tool(String name, String json) {
        return new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
    }

    private static QueuedStep step(String tool) {
        return new QueuedStep(
                tool, new JsonObject(), null, List.of(), GuardAction.WAIT, FailurePolicy.STOP_PLAN, 1, List.of());
    }

    private static Caller owner(ServerPlayer player) {
        return new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void set_goal_stores_the_goal_in_the_owner_record(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ToolResult r = call(
                c,
                owner(player),
                "set_goal",
                "{\"text\":\"gather 12 logs\",\"criteria\":[{\"text\":\"12 logs\",\"type\":\"has_item\",\"item\":\"minecraft:oak_log\",\"count\":12},{\"text\":\"tell the owner\"}]}");
        h.assertFalse(r.error(), "error: " + r.content());
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        var board = state.goals().board(player.getUUID());
        h.assertTrue(board.primary().isPresent(), "a primary goal exists");
        h.assertTrue(
                board.primary().get().criteria().get(0).check().isPresent(), "the guard criterion keeps its check");
        h.assertTrue(board.primary().get().criteria().get(1).check().isEmpty(), "the text criterion has no check");
        h.assertTrue(state.goalStore().status(player.getUUID()).currentRevision() > 0, "the goal record is dirty");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void goal_survives_dismiss_and_summon(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionManager manager = CompanionManager.get(h.getLevel().getServer());
        CompanionEntity c = manager.summon(player);
        h.assertFalse(
                call(c, owner(player), "set_goal", "{\"text\":\"build a house\"}")
                        .error(),
                "set_goal works");
        manager.dismiss(player);
        manager.summon(player);

        var state = GameTestSupport.runtime(h.getLevel().getServer());
        h.assertTrue(
                state.goals().board(player.getUUID()).primary().isPresent()
                        && state.goals()
                                .board(player.getUUID())
                                .primary()
                                .get()
                                .text()
                                .equals("build a house"),
                "the owner's board survives entity dismissal and summon");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void guest_cannot_set_goals(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ToolResult r = call(
                c,
                new Caller(java.util.UUID.randomUUID(), "Gus", AccessLevel.GUEST),
                "set_goal",
                "{\"text\":\"take over\"}");
        h.assertTrue(r.error(), "a guest must be refused");
        h.assertTrue(
                GameTestSupport.runtime(h.getLevel().getServer())
                        .goals()
                        .board(player.getUUID())
                        .primary()
                        .isEmpty(),
                "no goal was stored");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void trusted_player_cannot_set_goals_because_goals_run_with_owner_authority(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        ToolResult r = call(
                c,
                new Caller(java.util.UUID.randomUUID(), "Tom", AccessLevel.TRUSTED),
                "set_goal",
                "{\"text\":\"kill the villagers\"}");
        h.assertTrue(r.error(), "a trusted player must be refused");
        h.assertTrue(
                GameTestSupport.runtime(h.getLevel().getServer())
                        .goals()
                        .board(player.getUUID())
                        .primary()
                        .isEmpty(),
                "no goal was stored");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void second_primary_goal_is_an_error_and_finish_frees_the_slot(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"first\"}").error(), "first goal");
        h.assertTrue(call(c, owner, "set_goal", "{\"text\":\"second\"}").error(), "second primary is refused");
        long id = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .get()
                .id();
        h.assertFalse(
                call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"ok\"}")
                        .error(),
                "finish works");
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"second\"}").error(), "the slot is free again");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void abandoning_a_goal_keeps_its_reason_in_memory_and_frees_the_slot(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var board = runtime.goals().board(player.getUUID());
        h.assertFalse(
                call(c, owner, "set_goal", "{\"text\":\"Find a village\"}").error(), "sets a goal");
        long id = board.primary().orElseThrow().id();
        ToolResult finished = call(
                c,
                owner,
                "finish_goal",
                "{\"id\":" + id + ",\"done\":false,\"reason\":\"The owner asked to head home\"}");
        h.assertFalse(finished.error(), finished.content());
        var book = runtime.memory().book(player.getUUID());
        h.assertTrue(
                book.allFacts().stream()
                        .anyMatch(fact ->
                                fact.text().contains("abandoned, unverified; reason: The owner asked to head home")),
                "the abandonment reason reaches goal history");
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"Go home\"}").error(), "reuses the primary slot");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void a_trusted_players_unrelated_plan_does_not_attach_to_the_owners_goal(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(player);
        h.assertFalse(
                call(companion, owner(player), "set_goal", "{\"text\":\"Build my house\"}")
                        .error(),
                "goal set");
        var trusted = new Caller(java.util.UUID.randomUUID(), "friend", AccessLevel.TRUSTED);
        ToolResult result =
                call(companion, trusted, "queue_add", "{\"goal\":\"Jump\",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertFalse(result.error(), result.content());
        var board = GameTestSupport.runtime(h.getLevel().getServer()).goals().board(player.getUUID());
        h.assertTrue(board.primary().orElseThrow().planId() == 0, "an unrelated trusted plan must remain unlinked");
        var unrelatedOwner = call(
                companion,
                owner(player),
                "queue_add",
                "{\"goal\":\"Walk somewhere else\",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertFalse(unrelatedOwner.error(), unrelatedOwner.content());
        long goalId = board.primary().orElseThrow().id();
        var mismatched = call(
                companion,
                owner(player),
                "queue_add",
                "{\"goal\":\"Jump\",\"goal_id\":" + goalId + ",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertTrue(mismatched.error(), "a mismatched objective is refused even with the goal ID");
        h.assertTrue(board.primary().orElseThrow().planId() == 0, "unrelated owner work leaves the goal alone");
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void a_goal_and_plan_created_in_one_owner_turn_are_linked(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var owner = player.getUUID();
        var script = new CompanionControlTests.Script()
                .call("set_goal", "{\"text\":\"Wait here\"}")
                // The step outlives the assertion on purpose: a plan that ends releases its goal, so a plan short
                // enough to finish before this test observes the link would hide the very link it checks.
                .call(
                        "queue_add",
                        "{\"goal\":\"Wait here\",\"steps\":[{\"tool\":\"wait\",\"args\":\"{\\\"seconds\\\":30}\"}]}")
                .text("[silent]");
        runtime.sessions().setGatewayOverride(owner, script);
        CompanionManager.get(h.getLevel().getServer()).summon(player);
        runtime.sessions().post(owner, new BrainEvent.PlayerSaid(owner, "Steve", "Wait here", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            var goal = runtime.goals().board(owner).primary();
            h.assertTrue(goal.isPresent() && goal.orElseThrow().planId() > 0, "same-turn goal and plan must be linked");
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void queue_add_links_the_plan_to_the_primary_goal(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"Jump once\"}").error(), "goal set");
        long goalId = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow()
                .id();
        ToolResult r = call(
                c,
                owner,
                "queue_add",
                "{\"goal\":\"Jump once\",\"goal_id\":" + goalId + ",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertFalse(r.error(), "queue_add error: " + r.content());
        var goal = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .get();
        h.assertTrue(goal.planId() != 0, "the plan is linked to the goal");
        h.succeedWhen(() -> h.assertTrue(
                c.executionCoordinator().queue().archives().stream()
                        .anyMatch(result -> result.id() == goal.planId() && result.success()),
                "the linked jump settles successfully under its own plan"));
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void a_plan_that_ends_frees_its_goal_for_the_next_plan(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var owner = player.getUUID();
        // Running the tool through the session creates the brain whose task listener sees the plan's end.
        ToolResult set = runtime.sessions()
                .runTool(
                        owner,
                        tool(
                                "set_goal",
                                "{\"text\":\"Jump twice\",\"criteria\":[{\"text\":\"12 logs\",\"type\":\"has_item\",\"item\":\"minecraft:oak_log\",\"count\":12}]}"),
                        owner(player));
        h.assertFalse(set.error(), "set_goal error: " + set.content());
        var board = runtime.goals().board(owner);
        long goalId = board.primary().orElseThrow().id();
        long doomed = c.executionCoordinator()
                .enqueuePlan(
                        "Jump twice",
                        new PlanDefinition(List.of(new PlanNode.Action(step("missing_tool")))),
                        PlanOptions.DEFAULT,
                        Map.of(),
                        // The link lands before the plan's first step can run, as queue_add's own callback does.
                        planId -> board.linkPlan(goalId, planId));

        h.succeedWhen(() -> {
            h.assertTrue(
                    c.executionCoordinator().queue().archives().stream().anyMatch(result -> result.id() == doomed),
                    "the doomed plan reaches its terminal boundary");
            var goal = board.primary().orElseThrow();
            h.assertValueEqual(0L, goal.planId(), "the ended plan no longer pins the goal");
            h.assertValueEqual(1, goal.replans(), "the failed attempt charged exactly one replan");
            h.assertValueEqual(1, goal.criteria().size(), "the criteria the next plan must meet stay");
            ToolResult queued = runtime.sessions()
                    .runTool(
                            owner,
                            tool(
                                    "queue_add",
                                    "{\"goal\":\"Jump twice\",\"goal_id\":" + goalId
                                            + ",\"steps\":[{\"tool\":\"jump\"}]}"),
                            owner(player));
            h.assertFalse(queued.error(), "the freed goal accepts a new linked plan: " + queued.content());
            long relinked = board.primary().orElseThrow().planId();
            h.assertTrue(relinked > 0 && relinked != doomed, "the goal follows its new plan: " + relinked);
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void a_goal_whose_plan_is_no_longer_queued_accepts_the_next_plan(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionManager.get(h.getLevel().getServer()).summon(player);
        var runtime = GameTestSupport.runtime(h.getLevel().getServer());
        var owner = player.getUUID();
        ToolResult set =
                runtime.sessions().runTool(owner, tool("set_goal", "{\"text\":\"Jump twice\"}"), owner(player));
        h.assertFalse(set.error(), "set_goal error: " + set.content());
        var board = runtime.goals().board(owner);
        long goalId = board.primary().orElseThrow().id();
        // A restart archives the plan a goal was following; the stored link outlives the queue row.
        board.linkPlan(goalId, 9999);

        ToolResult queued = runtime.sessions()
                .runTool(
                        owner,
                        tool(
                                "queue_add",
                                "{\"goal\":\"Jump twice\",\"goal_id\":" + goalId + ",\"steps\":[{\"tool\":\"jump\"}]}"),
                        owner(player));

        h.assertFalse(queued.error(), "the goal is free once its plan is gone: " + queued.content());
        long linked = board.primary().orElseThrow().planId();
        h.assertTrue(linked > 0 && linked != 9999, "the new plan replaces the stale link: " + linked);
        h.succeed();
    }

    @GameTest(structure = GameTestArenas.ARENA_16)
    public void a_plan_queued_before_the_goal_links_by_its_id(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        ToolResult queued = call(c, owner, "queue_add", "{\"goal\":\"Jump once\",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertFalse(queued.error(), "queue_add error: " + queued.content());
        long planId = planIdIn(queued.content());
        h.assertTrue(planId > 0, "the queue reports the plan id: " + queued.content());
        h.assertFalse(
                call(c, owner, "set_goal", "{\"text\":\"Jump once\",\"plan\":" + planId + "}")
                        .error(),
                "set_goal with the returned id links the queued plan");
        var goal = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow();
        h.assertTrue(planId == goal.planId(), "the goal follows the plan queued before it: goal " + goal.planId());
        h.succeed();
    }

    private static long planIdIn(String content) {
        var match = java.util.regex.Pattern.compile("plan #(\\d{1,9})").matcher(content == null ? "" : content);
        return match.find() ? Long.parseLong(match.group(1)) : 0L;
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void finishingWithAnUnmetCriterionIsRefused(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(
                call(
                                c,
                                owner,
                                "set_goal",
                                "{\"text\":\"get a diamond\",\"criteria\":[{\"text\":\"has a diamond\","
                                        + "\"type\":\"has_item\",\"item\":\"minecraft:diamond\",\"count\":1}]}")
                        .error(),
                "goal with a checkable criterion");
        long id = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow()
                .id();

        var refused = call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"done\"}");
        h.assertTrue(refused.error(), "a completion claim the world cannot confirm must be refused");
        h.assertTrue(
                refused.content().contains("has a diamond"),
                "the refusal names the unmet criterion: " + refused.content());
        h.assertTrue(
                GameTestSupport.runtime(h.getLevel().getServer())
                        .goals()
                        .board(player.getUUID())
                        .primary()
                        .isPresent(),
                "the goal stays active after the refusal");

        c.inventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 1));
        h.assertFalse(
                call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"done\"}")
                        .error(),
                "with the item in hand the claim holds");
        h.succeed();
    }

    /** A set is four tools; a goal that records one of them must not finish the order. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void finishRefusesAFourToolOrderCoveredByOneItem(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(
                call(
                                c,
                                owner,
                                "set_goal",
                                "{\"text\":\"one stone pickaxe\","
                                        + "\"order\":\"make one stone tool set for each of us\","
                                        + "\"criteria\":[{\"text\":\"a pickaxe\","
                                        + "\"type\":\"handed_over\",\"item\":\"minecraft:stone_pickaxe\",\"count\":1}]}")
                        .error(),
                "a narrow goal is still recorded, with a warning");
        long id = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow()
                .id();
        var refused = call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"done\"}");
        h.assertTrue(refused.error(), "one tool cannot finish a four-tool order");
        h.assertTrue(
                refused.content().contains("minecraft:stone_axe"),
                "the refusal names a missing tool: " + refused.content());
        h.succeed();
    }

    /** Stock the owner already had is not a hand-over. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void existingOwnerStockDoesNotProveANewHandover(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_AXE));
        var facts = new dev.friendai.task.queue.CompanionGuardFacts(c);
        facts.refresh();
        h.assertTrue(facts.ownerCountItems("minecraft:stone_axe") >= 1, "the owner really holds an axe");
        h.assertValueEqual(
                facts.handedOverItems(
                        "minecraft:stone_axe", Math.max(0L, h.getLevel().getGameTime() - 100)),
                0,
                "held stock is not a hand-over");
        h.assertFalse(
                new dev.friendai.execution.queue.Guard.HandedOver(
                                "minecraft:stone_axe",
                                1,
                                Math.max(0L, h.getLevel().getGameTime() - 100))
                        .holds(facts),
                "a handed_over criterion cannot be satisfied by existing stock");
        h.assertTrue(
                new dev.friendai.execution.queue.Guard.Possesses("minecraft:stone_axe", 1).holds(facts),
                "the possession criterion still reads the owner's inventory");
        h.succeed();
    }

    /** A partial pickup and a reserved companion share are different numbers. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void partialPickupAndReservedStockStayDistinct(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        long now = h.getLevel().getGameTime();
        c.recordDelivery(new dev.friendai.features.inventory.DeliveredLedger.Entry(
                "minecraft:oak_log", 4, now, java.util.UUID.randomUUID(), 0));
        c.inventory().addItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 4));
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
        var facts = new dev.friendai.task.queue.CompanionGuardFacts(c);
        facts.refresh();
        h.assertValueEqual(facts.handedOverItems("minecraft:oak_log", now), 4, "four logs left the companion");
        h.assertValueEqual(facts.pickedUpItems("minecraft:oak_log", now), 2, "the owner picked up only two");
        h.assertValueEqual(facts.countItems("minecraft:oak_log"), 4, "the companion kept the reserved share");
        h.assertTrue(
                new dev.friendai.execution.queue.Guard.PickedUp("minecraft:oak_log", 2, now).holds(facts),
                "the partial pickup is proven");
        h.assertFalse(
                new dev.friendai.execution.queue.Guard.PickedUp("minecraft:oak_log", 4, now).holds(facts),
                "the partial pickup is not a full pickup");
        h.succeed();
    }

    /** "Do I have X" stays a possession question rather than becoming a delivery. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 300)
    public void possessionQuestionStillFinishes(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        player.getInventory().add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        h.assertFalse(
                call(
                                c,
                                owner,
                                "set_goal",
                                "{\"text\":\"check diamonds\",\"order\":\"do I have 3 diamonds?\","
                                        + "\"criteria\":[{\"text\":\"owner holds 3 diamonds\","
                                        + "\"type\":\"possesses\",\"item\":\"minecraft:diamond\",\"count\":3}]}")
                        .error(),
                "the possession goal is accepted");
        long id = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow()
                .id();
        var before = call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"checked\"}");
        if (before.error()) throw new IllegalStateException("possession refusal: " + before.content());
        var finished = before;
        h.assertFalse(finished.error(), "with the diamonds held, the question is answered: " + finished.content());
        h.succeed();
    }

    /** A build order that names a roof cannot be met by a plan that only lays a floor and walls. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void buildWithAMissingPartIsRefusedBeforeQueueing(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        var refused = call(
                c,
                owner,
                "queue_add",
                "{\"goal\":\"build a small house with a roof and a door\","
                        + "\"steps\":[{\"tool\":\"build_structure\",\"args\":\"{\\\"generator\\\":"
                        + "\\\"{\\\\\\\"type\\\\\\\":\\\\\\\"house\\\\\\\",\\\\\\\"walls\\\\\\\":"
                        + "\\\\\\\"minecraft:oak_planks\\\\\\\"}\\\","
                        + "\\\"parts\\\":[\\\"floor\\\",\\\"walls\\\"]}\"}]}");
        h.assertTrue(refused.error(), "a plan that omits the roof must not enter the queue");
        h.assertTrue(refused.content().contains("roof"), "the refusal names the missing part: " + refused.content());
        h.assertValueEqual(c.executionCoordinator().queue().planCount(), 0, "nothing was queued");
        h.succeed();
    }

    /** A queued delivery is intent: until the hand-over is recorded the order stays open. */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aQueuedDeliveryPlanDoesNotFinishTheOrder(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(
                call(
                                c,
                                owner,
                                "set_goal",
                                "{\"text\":\"bring two oak logs\",\"order\":\"give me 2 oak logs\","
                                        + "\"criteria\":[{\"text\":\"two logs handed over\","
                                        + "\"type\":\"handed_over\",\"item\":\"minecraft:oak_log\",\"count\":2}]}")
                        .error(),
                "the order is recorded");
        long id = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .orElseThrow()
                .id();
        h.assertFalse(
                call(
                                c,
                                owner,
                                "queue_add",
                                "{\"goal\":\"bring two oak logs\",\"goal_id\":" + id + ","
                                        + "\"steps\":[{\"tool\":\"give_items\",\"args\":"
                                        + "\"{\\\"item\\\":\\\"minecraft:oak_log\\\",\\\"count\\\":2}\"}]}")
                        .error(),
                "the plan that will deliver both logs is admitted");
        var refused = call(c, owner, "finish_goal", "{\"id\":" + id + ",\"done\":true,\"reason\":\"queued\"}");
        h.assertTrue(refused.error(), "a queued job is not a completed delivery");
        h.assertTrue(
                refused.content().contains("handed over"),
                "the refusal names the missing delivery: " + refused.content());
        h.succeed();
    }

    /**
     * The panel's goal box and the model's {@code update_goal} are one mutation path: a saved edit is scheduled for
     * the owner record, and a refused one (a goal that is gone, or a blank text) writes nothing.
     */
    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void a_panel_goal_edit_is_written_to_the_owner_record(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        CompanionEntity c = CompanionManager.get(server).summon(player);
        h.assertFalse(
                call(c, owner(player), "set_goal", "{\"text\":\"gather 12 logs\"}")
                        .error(),
                "set_goal works");
        var state = GameTestSupport.runtime(server);
        var board = state.goals().board(player.getUUID());
        long goalId = board.primary().orElseThrow().id();
        state.goalStore().flushAll();
        h.succeedWhen(() -> {
            h.assertFalse(state.goalStore().status(player.getUUID()).dirty(), "the set_goal write landed");
            TestMenuRequests.handle(player, new ControlRequest.UpdateGoal(goalId + 1, "gone"));
            TestMenuRequests.handle(player, new ControlRequest.UpdateGoal(goalId, "  "));
            h.assertFalse(
                    state.goalStore().status(player.getUUID()).dirty(), "a refused panel edit schedules no write");
            TestMenuRequests.handle(player, new ControlRequest.UpdateGoal(goalId, "gather 12 oak logs"));
            h.assertValueEqual(
                    board.primary().orElseThrow().text(), "gather 12 oak logs", "the panel edit reached the board");
            h.assertTrue(
                    state.goalStore().status(player.getUUID()).dirty(),
                    "the accepted panel edit is scheduled for the owner record");
        });
    }
}
