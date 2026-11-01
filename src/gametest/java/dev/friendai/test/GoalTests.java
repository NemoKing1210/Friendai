package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
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

    @GameTest
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

    @GameTest
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

    @GameTest
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

    @GameTest
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

    @GameTest
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

    @GameTest
    public void queue_add_links_the_plan_to_the_primary_goal(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        CompanionEntity c = CompanionManager.get(h.getLevel().getServer()).summon(player);
        Caller owner = owner(player);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"stay put\"}").error(), "goal set");
        ToolResult r = call(c, owner, "queue_add", "{\"goal\":\"stay\",\"steps\":[{\"tool\":\"jump\"}]}");
        h.assertFalse(r.error(), "queue_add error: " + r.content());
        var goal = GameTestSupport.runtime(h.getLevel().getServer())
                .goals()
                .board(player.getUUID())
                .primary()
                .get();
        h.assertTrue(goal.planId() != 0, "the plan is linked to the goal");
        h.succeed();
    }

    @GameTest(maxTicks = 300)
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
}
