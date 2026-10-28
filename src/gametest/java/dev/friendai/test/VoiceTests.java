package dev.friendai.test;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.net.VoiceTranscriptHandler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
        FriendaiServerState.get(server).sessions().setGatewayOverride(player.getUUID(), recorder);
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
            h.assertTrue(
                    first.get(first.size() - 1).text().equals(expected),
                    "message was " + first.get(first.size() - 1).text());
        });
    }

    @GameTest(maxTicks = 100)
    public void foreignThreadTrustAnswerIsDeferred(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        // Only a companion close enough to hear the answer may consume it; without one the line
        // becomes an ordinary guest message to whichever companion is nearby.
        setUp(h, player);
        var state = FriendaiServerState.get(h.getLevel().getServer());
        UUID target = UUID.randomUUID();
        state.trust().request(player, target, "Bob", true);
        Thread caller = new Thread(() -> VoiceTranscriptHandler.handle(player, "yes"), "voice-test-caller");
        caller.start();
        try {
            caller.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        h.assertTrue(
                !state.memory().book(player.getUUID()).isTrusted(target),
                "the trust answer was applied on the calling thread");
        h.succeedWhen(() -> h.assertTrue(
                state.memory().book(player.getUUID()).isTrusted(target),
                "the trust answer never reached the server thread"));
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
