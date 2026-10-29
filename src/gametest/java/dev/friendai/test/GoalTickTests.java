package dev.friendai.test;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.infrastructure.config.ServerConfig;
import dev.friendai.server.agent.CompanionSessions;
import dev.friendai.server.runtime.ServerRuntime;
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
                String system, List<Message> messages, List<dev.friendai.agent.llm.model.ToolSpec> tools) {
            messages.forEach(m -> userLines.add(m.text()));
            return CompletableFuture.completedFuture(
                    new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0)));
        }

        boolean sawNudge() {
            return userLines.stream().anyMatch(line -> line.contains("is not finished"));
        }
    }

    private static dev.friendai.execution.tool.ToolResult setGoal(CompanionEntity c, ServerPlayer player) {
        String json = "{\"text\":\"get a pickaxe\"}";
        ToolCall call =
                new ToolCall("1", "set_goal", JsonParser.parseString(json).getAsJsonObject(), json, null);
        return GameTestToolRegistry.create(c, () -> 32)
                .bind(c)
                .execute(call, new Caller(player.getUUID(), "Steve", AccessLevel.OWNER));
    }

    private static ServerConfig fastTicks(String autonomy) {
        ServerConfig config = ServerConfig.defaults();
        config.agent.goalTickSec = 10;
        config.agent.autonomy = autonomy;
        return config;
    }

    /**
     * One test for both levels: the autonomy setting is server-wide, so two tests flipping it in parallel would
     * see each other's setting. First an assistive companion is nudged, then a passive one is not.
     */
    @GameTest(maxTicks = 1_200)
    public void assistive_companion_is_nudged_and_passive_one_is_not(GameTestHelper h) {
        GameTestSupport.platform(h, 16);
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 4, 2, 4);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        ServerConfig original = state.config();
        state.applyConfig(fastTicks("assistive"));
        CompanionSessions brains = state.sessions();
        Recorder recorder = new Recorder();
        brains.setGatewayOverride(player.getUUID(), recorder);
        CompanionEntity c = CompanionManager.get(server).summon(player);
        brains.post(player.getUUID(), new BrainEvent.WorldNotice("ready"));
        var goalResult = setGoal(c, player);
        h.assertFalse(goalResult.error(), "set_goal failed: " + goalResult.content());
        h.assertTrue(
                state.goals().board(player.getUUID()).primary().isPresent(), "set_goal did not persist a primary goal");
        h.runAfterDelay(600, () -> {
            var runner = c.executionCoordinator();
            var session = brains.find(player.getUUID()).orElse(null);
            var primary = state.goals().board(player.getUUID()).primary().orElseThrow();
            h.assertTrue(
                    recorder.sawNudge(),
                    "an idle companion with a goal should be nudged; saw " + recorder.userLines
                            + "; owner=" + c.owner().isPresent()
                            + ", thinking=" + (session != null && session.isThinking())
                            + ", work=" + runner.hasWork()
                            + ", jobs=" + runner.queue().jobCount()
                            + ", active=" + runner.queue().active().isPresent()
                            + ", paused=" + runner.paused()
                            + ", suspended=" + runner.suspended()
                            + ", sleeping=" + c.isSleeping()
                            + ", target=" + (c.getTarget() != null)
                            + ", goal=" + primary.status()
                            + ", autonomy=" + state.config().agent.autonomy
                            + ", interval=" + state.config().agent.goalTickSec
                            + ", serverTicks=" + server.getTickCount()
                            + ", levelTicks=" + h.getLevel().getGameTime());
            state.applyConfig(fastTicks("passive"));
            int before = recorder.userLines.size();
            h.runAfterDelay(450, () -> {
                state.applyConfig(original);
                long nudgesAfter = recorder.userLines.subList(before, recorder.userLines.size()).stream()
                        .filter(line -> line.contains("is not finished"))
                        .count();
                h.assertTrue(
                        nudgesAfter == 0,
                        "a passive companion must not start on its own; saw "
                                + recorder.userLines.subList(before, recorder.userLines.size()));
                h.succeed();
            });
        });
    }
}
