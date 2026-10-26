package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.brain.BrainEvent;
import dev.friendai.brain.LlmGateway;
import dev.friendai.config.ServerConfig;
import dev.friendai.core.BrainService;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.model.Message;
import dev.friendai.llm.model.StopReason;
import dev.friendai.llm.model.ToolCall;
import dev.friendai.llm.model.Usage;
import dev.friendai.tool.AccessLevel;
import dev.friendai.tool.Caller;
import dev.friendai.tool.builtin.FriendaiTools;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class GoalTickTests {
    private static final class Recorder implements LlmGateway {
        final List<String> userLines = new ArrayList<>();

        @Override
        public CompletableFuture<LlmResponse> complete(
                String system, List<Message> messages, List<dev.friendai.llm.model.ToolSpec> tools) {
            messages.forEach(m -> userLines.add(m.text()));
            return CompletableFuture.completedFuture(new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        }

        boolean sawNudge() {
            return userLines.stream().anyMatch(line -> line.contains("is not finished"));
        }
    }

    private static void setGoal(CompanionEntity c, ServerPlayer player) {
        String json = "{\"text\":\"get a pickaxe\"}";
        ToolCall call = new ToolCall("1", "set_goal", JsonParser.parseString(json).getAsJsonObject(), json, null);
        FriendaiTools.create(() -> 32).bind(c).execute(call, new Caller(player.getUUID(), "Steve", AccessLevel.OWNER));
    }

    private static ServerConfig fastTicks(String autonomy) {
        ServerConfig config = ServerConfig.defaults();
        config.agent.goalTickSec = 10;
        config.agent.autonomy = autonomy;
        return config;
    }

    @GameTest(maxTicks = 600)
    public void idle_companion_with_a_goal_gets_a_nudge(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        ServerConfig original = state.config();
        state.applyConfig(fastTicks("assistive"));
        BrainService brains = state.brains();
        Recorder recorder = new Recorder();
        brains.setGatewayOverride(player.getUUID(), recorder);
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        setGoal(c, player);
        h.succeedWhen(() -> {
            h.assertTrue(recorder.sawNudge(), "an idle companion with a goal should be nudged; saw " + recorder.userLines);
            state.applyConfig(original);
        });
    }

    @GameTest(maxTicks = 400)
    public void passive_companion_is_never_nudged(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        ServerConfig original = state.config();
        state.applyConfig(fastTicks("passive"));
        BrainService brains = state.brains();
        Recorder recorder = new Recorder();
        brains.setGatewayOverride(player.getUUID(), recorder);
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        setGoal(c, player);
        h.runAfterDelay(300, () -> {
            state.applyConfig(original);
            h.assertFalse(recorder.sawNudge(), "a passive companion must not start on its own");
            h.succeed();
        });
    }
}
