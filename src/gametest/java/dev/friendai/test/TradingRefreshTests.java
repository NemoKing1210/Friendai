package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.trading.minecraft.task.RefreshTradesTask;
import dev.friendai.server.agent.perception.PerceptionContext;
import dev.friendai.server.agent.tools.FriendaiTools;
import dev.friendai.task.Task;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

public final class TradingRefreshTests {
    private static ToolResult run(ToolExecutor tools, String name, String json, Caller caller) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        return tools.execute(new ToolCall("refresh-test", name, args, json, null), caller);
    }

    @GameTest
    public void ownerCanScheduleAnExactMerchantRefresh(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        var owner = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        companion.setOwner(owner.getUUID());
        var villager = h.spawn(EntityTypes.VILLAGER, 3, 2, 2);
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        ToolExecutor tools = FriendaiTools.build(
                        () -> 32,
                        () -> 32,
                        dev.friendai.features.memory.minecraft.tool.MemoryAccess.DISABLED,
                        () -> new PerceptionContext(state.resourceCandidates(), state.settings(), state.freeTime()),
                        () -> true,
                        () -> true,
                        state.resourceCandidates(),
                        state.tradingAccess())
                .registry()
                .bind(companion);

        ToolResult result = run(
                tools,
                "refresh_trades",
                "{\"traders\":[\"" + villager.getUUID() + "\"]}",
                new Caller(owner.getUUID(), "owner", AccessLevel.OWNER));

        h.assertFalse(result.error(), "explicit owner refresh was rejected: " + result.content());
        h.assertTrue(
                companion
                        .executionCoordinator()
                        .current()
                        .map(task -> task.type().equals("refresh_trades"))
                        .orElse(false),
                "owner request did not start the bounded refresh task");
        h.succeed();
    }

    /**
     * An idle companion near a trader it cannot reach used to walk to it every 200 ticks, and each failed visit woke
     * the model: a live session spent a model turn every few seconds on "I can't reach the trader". The idle refresh
     * now visits one trader once per retry window and keeps its outcome out of the brain.
     */
    @GameTest(maxTicks = 1800)
    public void idleRefreshVisitsABoxedTraderOnceAndKeepsItFromTheBrain(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        var server = h.getLevel().getServer();
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        CompanionEntity companion = CompanionManager.get(server).summon(owner);
        // Glass all round and on top: the villager is in plain sight and out of reach.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 2; y <= 4; y++) {
                    if (dx != 0 || dz != 0 || y == 4) h.setBlock(11 + dx, y, 11 + dz, Blocks.GLASS);
                }
            }
        }
        UUID villager = h.spawn(EntityTypes.VILLAGER, 11, 2, 11).getUUID();
        Set<Task> visits = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> loud = new ArrayList<>();
        h.onEachTick(() -> companion
                .executionCoordinator()
                .current()
                .filter(task -> task instanceof RefreshTradesTask refresh
                        && refresh.traders().contains(villager))
                .ifPresent(task -> {
                    if (visits.add(task) && task.emitsEvents()) loud.add(task.describe());
                }));
        h.runAfterDelay(1600, () -> {
            h.assertValueEqual(visits.size(), 1, "idle visits to the boxed trader within the retry window");
            h.assertTrue(loud.isEmpty(), "an idle refresh reached the brain: " + loud);
            h.succeed();
        });
    }
}
