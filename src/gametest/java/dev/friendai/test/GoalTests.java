package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class GoalTests {
    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32).bind(c).execute(call, caller);
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
                .call("set_goal", "{\"text\":\"Jump twice\"}")
                .call("queue_add", "{\"goal\":\"Jump twice\",\"steps\":[{\"tool\":\"jump\"},{\"tool\":\"jump\"}]}")
                .text("[silent]");
        runtime.sessions().setGatewayOverride(owner, script);
        CompanionManager.get(h.getLevel().getServer()).summon(player);
        runtime.sessions().post(owner, new BrainEvent.PlayerSaid(owner, "Steve", "Jump twice", BrainEvent.Source.CHAT));
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
}
