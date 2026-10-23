package dev.friedai.test;

import dev.friedai.brain.LlmGateway;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionManager;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.StopReason;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.llm.model.Usage;
import dev.friedai.net.VoiceTranscriptHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class VoiceTests {
    private static final class Recorder implements LlmGateway {
        final List<List<Message>> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new ArrayList<>(messages));
            return CompletableFuture.completedFuture(new LlmResponse("ok", List.of(), StopReason.END, new Usage(0, 0)));
        }
    }

    private static Recorder setUp(GameTestHelper h, ServerPlayer player) {
        var server = h.getLevel().getServer();
        Recorder recorder = new Recorder();
        FriedaiServerState.get(server).brains().setGatewayOverride(player.getUUID(), recorder);
        CompanionManager.get(server).summon(player);
        return recorder;
    }

    @GameTest(maxTicks = 100)
    public void voiceTranscriptReachesBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        Recorder recorder = setUp(h, player);
        VoiceTranscriptHandler.handle(player, "stay");
        String expected = "[" + player.getName().getString() + " (owner), by voice]: stay";
        h.succeedWhen(() -> {
            h.assertTrue(!recorder.requests.isEmpty(), "no LLM request yet");
            List<Message> first = recorder.requests.get(0);
            h.assertTrue(first.get(first.size() - 1).text().equals(expected), "message was " + first.get(first.size() - 1).text());
        });
    }

    @GameTest(maxTicks = 100)
    public void rateLimitedSecondMessageIgnored(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        Recorder recorder = setUp(h, player);
        VoiceTranscriptHandler.handle(player, "first");
        VoiceTranscriptHandler.handle(player, "second");
        h.runAfterDelay(20, () -> {
            h.assertTrue(recorder.requests.size() == 1, "requests: " + recorder.requests.size());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void emptyAndOversizedTranscriptsAreIgnored(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        Recorder recorder = setUp(h, player);
        VoiceTranscriptHandler.handle(player, "   ");
        VoiceTranscriptHandler.handle(player, "x".repeat(501));
        h.runAfterDelay(10, () -> {
            h.assertTrue(recorder.requests.isEmpty(), "nothing should have reached the brain");
            h.succeed();
        });
    }
}
