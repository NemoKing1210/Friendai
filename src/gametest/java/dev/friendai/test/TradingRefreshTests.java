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
import dev.friendai.perception.PerceptionContext;
import dev.friendai.tool.builtin.FriendaiTools;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;

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
        var state = dev.friendai.core.FriendaiServerState.get(h.getLevel().getServer());
        ToolExecutor tools = FriendaiTools.build(
                        () -> 32,
                        () -> 32,
                        dev.friendai.tool.builtin.MemoryAccess.DISABLED,
                        () -> new PerceptionContext(state.resources(), state.settings(), state.freeTime()),
                        () -> true,
                        () -> true,
                        state.resources(),
                        state)
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
}
