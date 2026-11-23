package dev.friendai.test;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.server.agent.chat.ChatRouter;
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
        GameTestSupport.runtime(server).sessions().setGatewayOverride(player.getUUID(), recorder);
        CompanionManager.get(server).summon(player);
        return recorder;
    }

    @GameTest(maxTicks = 100)
    public void voiceTranscriptReachesBrain(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        Recorder recorder = setUp(h, player);
        // A line the local-order grammar does not claim, so the test keeps measuring the voice-to-brain path.
        TestMenuRequests.voice(player, "hello there");
        String expected = "[" + player.getName().getString() + " (owner), by voice]: hello there";
        h.succeedWhen(() -> {
            h.assertTrue(!recorder.requests.isEmpty(), "no LLM request yet");
            List<Message> first = recorder.requests.get(0);
            h.assertTrue(
                    first.get(first.size() - 1).text().equals(expected),
                    "message was " + first.get(first.size() - 1).text());
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aSpokenStopOrderRunsWithoutAModel(GameTestHelper h) {
        var owner = online(h, "voice-stop", 71);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner);
        Recorder recorder = new Recorder();
        GameTestSupport.runtime(h).sessions().setGatewayOverride(owner.getUUID(), recorder);
        ChatRouter.onChat(GameTestSupport.runtime(h), owner, "@ stay");
        h.assertValueEqual(currentTask(companion), "stay", "the chat order starts the stay");
        TestMenuRequests.voice(owner, "стоп");
        h.assertTrue(!"stay".equals(currentTask(companion)), "the spoken stop must end the stay");
        h.runAfterDelay(10, () -> {
            h.assertTrue(recorder.requests.isEmpty(), "the spoken order must not reach the model");
            h.succeed();
        });
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aSpokenQuestionStaysConversation(GameTestHelper h) {
        var owner = online(h, "voice-question", 73);
        CompanionManager.get(h.getLevel().getServer()).summon(owner);
        Recorder recorder = new Recorder();
        GameTestSupport.runtime(h).sessions().setGatewayOverride(owner.getUUID(), recorder);
        TestMenuRequests.voice(owner, "why did you stop?");
        h.succeedWhen(() -> h.assertTrue(!recorder.requests.isEmpty(), "a near-miss question never reached the model"));
    }

    @GameTest(structure = GameTestArenas.ARENA_16, maxTicks = 200)
    public void aGuestsSpokenOrderIsRefused(GameTestHelper h) {
        GameTestSupport.platformAt(h, 75, 16);
        var owner = GameTestSupport.onlinePlayer(h, "voice-owner", 2, 75, 2, true);
        var guest = GameTestSupport.onlinePlayer(h, "voice-guest", 3, 75, 3, true);
        CompanionEntity companion =
                CompanionManager.get(h.getLevel().getServer()).summon(owner.player());
        Recorder recorder = new Recorder();
        GameTestSupport.runtime(h).sessions().setGatewayOverride(owner.player().getUUID(), recorder);
        TestMenuRequests.voice(guest.player(), "остановись");
        h.assertTrue(
                companion.executionCoordinator().current().isEmpty(), "a guest's spoken order must not start work");
        h.assertTrue(
                guest.drainSystemMessages().stream()
                        .anyMatch(message -> message.getString().contains("access")),
                "a guest must be told they have no access");
        h.succeed();
    }

    private static ServerPlayer online(GameTestHelper h, String name, int y) {
        GameTestSupport.platformAt(h, y - 1, 16);
        return GameTestSupport.onlinePlayer(h, name, 2, y, 2, true).player();
    }

    private static String currentTask(CompanionEntity companion) {
        return companion
                .executionCoordinator()
                .current()
                .map(task -> task.type())
                .orElse("");
    }

    @GameTest(maxTicks = 100)
    public void foreignThreadTrustAnswerIsDeferred(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        // Only a companion close enough to hear the answer may consume it; without one the line
        // becomes an ordinary guest message to whichever companion is nearby.
        setUp(h, player);
        var state = GameTestSupport.runtime(h.getLevel().getServer());
        UUID target = UUID.randomUUID();
        state.trust().request(player, target, "Bob", true);
        Thread caller = new Thread(() -> TestMenuRequests.voice(player, "yes"), "voice-test-caller");
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
        // Plain chat: a planning line would add planner requests of its own and the count would stop measuring the
        // throttle.
        TestMenuRequests.voice(player, "hello");
        TestMenuRequests.voice(player, "again");
        h.runAfterDelay(20, () -> {
            h.assertTrue(recorder.requests.size() == 1, "requests: " + recorder.requests.size());
            h.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void emptyAndOversizedTranscriptsAreIgnored(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        Recorder recorder = setUp(h, player);
        TestMenuRequests.voice(player, "   ");
        TestMenuRequests.voice(player, "x".repeat(501));
        h.runAfterDelay(10, () -> {
            h.assertTrue(recorder.requests.isEmpty(), "nothing should have reached the brain");
            h.succeed();
        });
    }
}
