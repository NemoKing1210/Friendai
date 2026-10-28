package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class TradingToolsTests {
    private static final List<String> TRADING_TOOLS = List.of(
            "find_traders",
            "inspect_trader",
            "refresh_trades",
            "wait_for_restock",
            "known_trades",
            "plan_trade",
            "trade_item",
            "level_trader",
            "barter");

    private static ToolExecutor tools(CompanionEntity companion) {
        return FriendaiTools.create(() -> 32).bind(companion);
    }

    private static ToolResult run(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("1", name, args, json, null), caller);
    }

    @GameTest
    public void tradingToolsAreOwnerOnlyAndRejectUnknownArguments(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        ToolExecutor tools = tools(companion);
        Set<String> ownerNames = tools.specs(AccessLevel.OWNER).stream()
                .map(spec -> spec.name())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> trustedNames = tools.specs(AccessLevel.TRUSTED).stream()
                .map(spec -> spec.name())
                .collect(java.util.stream.Collectors.toSet());
        h.assertTrue(ownerNames.containsAll(TRADING_TOOLS), "owner-visible tools: " + ownerNames);
        h.assertTrue(
                TRADING_TOOLS.stream().noneMatch(trustedNames::contains), "trading tools must not be trusted-visible");

        ToolResult denied =
                run(tools, "find_traders", "{}", new Caller(UUID.randomUUID(), "trusted", AccessLevel.TRUSTED));
        h.assertTrue(denied.error(), "trusted caller should be denied");
        ToolResult guestDenied =
                run(tools, "find_traders", "{}", new Caller(UUID.randomUUID(), "guest", AccessLevel.GUEST));
        h.assertTrue(guestDenied.error(), "guest caller should be denied");

        ToolResult ownerCall = run(tools, "find_traders", "{}", new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(!ownerCall.error(), "owner should be able to run discovery");

        ToolResult extra = run(
                tools,
                "known_trades",
                "{\"direction\":\"buy\",\"item_id\":\"minecraft:emerald\",\"surprise\":true}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(extra.error(), "unknown fields must be rejected");

        ToolResult malformedJson = tools.execute(
                new ToolCall("2", "known_trades", null, "{\"direction\":", null),
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(malformedJson.error(), "malformed JSON arguments must be rejected");

        ToolResult missingBudget = run(
                tools,
                "trade_item",
                "{\"trader\":\"00000000-0000-0000-0000-000000000001\",\"offer_ref\":\"offer-x\",\"revision\":1,\"desired_output_count\":1,\"allow_overshoot\":false,\"allow_partial\":false,\"deadline_ticks\":20}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(missingBudget.error(), "a spending order must explicitly provide input caps");

        ToolResult overflow = run(
                tools,
                "known_trades",
                "{\"direction\":\"buy\",\"item_id\":\"minecraft:emerald\",\"limit\":2147483647}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(overflow.error(), "query limit overflow must be rejected");
        h.succeed();
    }

    @GameTest
    public void inspectionRejectsMalformedTraderUuid(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        ToolResult result = run(
                tools(companion),
                "inspect_trader",
                "{\"trader\":\"not-a-uuid\"}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(result.error(), "malformed UUID must be rejected");
        h.succeed();
    }

    @GameTest
    public void tradeItemRejectsForeignTraderReference(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        Caller ownerTurn = dev.friendai.core.FriendaiServerState.get(
                        h.getLevel().getServer())
                .sessions()
                .tradeAuthorityFor(owner)
                .begin(new Caller(owner, "owner", AccessLevel.OWNER), true);
        ToolResult result = run(
                tools(companion),
                "trade_item",
                "{\"trader\":\"00000000-0000-0000-0000-000000000001\",\"offer_ref\":\"offer-x\",\"revision\":1,\"desired_output_count\":1,\"input_caps\":[{\"item_id\":\"minecraft:emerald\",\"max_count\":4}],\"allow_overshoot\":false,\"allow_partial\":false,\"deadline_ticks\":20}",
                ownerTurn);
        h.assertTrue(
                result.error() && result.content().contains("unknown trader UUID"),
                "unknown owner-book trader must be rejected: " + result.content());
        h.succeed();
    }

    @GameTest
    public void genericGoldThrowCannotBarterWithPiglin(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        UUID owner = UUID.randomUUID();
        companion.setOwner(owner);
        companion.inventory().addItem(new ItemStack(Items.GOLD_INGOT, 4));
        h.spawn(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:piglin")), 4, 2, 2);

        ToolResult targeted = run(
                tools(companion),
                "throw_item",
                "{\"item\":\"minecraft:gold_ingot\",\"count\":1,\"target\":\"piglin\"}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(targeted.error(), "generic targeted gold throw must not initiate barter");
        h.assertTrue(companion.inventory().countItem(Items.GOLD_INGOT) == 4, "targeted gold must remain in inventory");

        ToolResult ordinary = run(
                tools(companion),
                "throw_item",
                "{\"item\":\"minecraft:gold_ingot\",\"count\":1}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(!ordinary.error(), "ordinary untargeted gold drops remain available");

        ToolResult interact = run(
                tools(companion),
                "interact_entity",
                "{\"target\":\"piglin\",\"item\":\"minecraft:gold_ingot\"}",
                new Caller(owner, "owner", AccessLevel.OWNER));
        h.assertTrue(interact.error(), "generic entity interaction must not barter");
        h.succeed();
    }
}
