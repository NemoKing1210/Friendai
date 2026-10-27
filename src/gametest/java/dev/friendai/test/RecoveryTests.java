package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.goal.GoalStatus;
import dev.friendai.brain.LlmGateway;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.Usage;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.ToolResult;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class RecoveryTests {
    private static final String FAILING_PLAN =
            "{\"goal\":\"tools\",\"steps\":[{\"tool\":\"craft_item\",\"args\":\"{\\\"item\\\":\\\"minecraft:diamond_pickaxe\\\",\\\"count\\\":1}\"}]}";

    private static ToolResult call(CompanionEntity c, Caller caller, String name, String json) {
        ToolCall call = new ToolCall("1", name, JsonParser.parseString(json).getAsJsonObject(), json, null);
        return FriendaiTools.create(() -> 32).bind(c).execute(call, caller);
    }

    private static LlmGateway silent() {
        return (system, messages, tools) -> CompletableFuture.completedFuture(
                new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
    }

    private static boolean logHas(BrainService brains, ServerPlayer player, String text) {
        List<LogEntry> log = brains.debug().logOf(player.getUUID()).after(0, 200);
        return log.stream().anyMatch(e -> e.kind() == LogKind.TASK && e.text().contains(text));
    }

    @GameTest(maxTicks = 400)
    public void failed_plan_event_carries_the_diagnosis(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        BrainService brains = FriendaiServerState.get(server).brains();
        brains.setGatewayOverride(player.getUUID(), silent());
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The brain, and with it the task listener, is created on its first event.
        brains.post(player.getUUID(), new dev.friendai.brain.BrainEvent.WorldNotice("test"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        ToolResult r = call(c, owner, "queue_add", FAILING_PLAN);
        h.assertFalse(r.error(), "queue_add: " + r.content());
        h.succeedWhen(() -> h.assertTrue(logHas(brains, player, "diagnosis: MISSING_ITEM"),
                "the plan event should carry a diagnosis; log: "
                        + brains.debug().logOf(player.getUUID()).after(0, 200)));
    }

    @GameTest(maxTicks = 400)
    public void spent_replan_budget_blocks_the_goal_and_asks_the_player(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        ServerConfig original = state.config();
        ServerConfig noReplans = ServerConfig.defaults();
        noReplans.agent.maxReplans = 0;
        state.applyConfig(noReplans);
        BrainService brains = state.brains();
        brains.setGatewayOverride(player.getUUID(), silent());
        CompanionEntity c = CompanionManager.get(server).summon(player);
        // The brain, and with it the task listener, is created on its first event.
        brains.post(player.getUUID(), new dev.friendai.brain.BrainEvent.WorldNotice("test"));
        Caller owner = new Caller(player.getUUID(), "Steve", AccessLevel.OWNER);
        h.assertFalse(call(c, owner, "set_goal", "{\"text\":\"get a pickaxe\"}").error(), "goal set");
        h.assertFalse(call(c, owner, "queue_add", FAILING_PLAN).error(), "queue_add");
        h.succeedWhen(() -> {
            h.assertTrue(logHas(brains, player, "replan budget spent"), "the event should say the budget is spent");
            h.assertTrue(state.goals().board(player.getUUID()).primary().orElseThrow().status() == GoalStatus.BLOCKED,
                    "the goal should be blocked");
            state.applyConfig(original);
        });
    }
}
