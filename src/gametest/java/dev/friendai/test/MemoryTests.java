package dev.friendai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.server.runtime.ServerRuntimeRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Facts survive a new summon, and a guest cannot write about the owner. */
public class MemoryTests {
    private record Request(String system, List<Message> messages, List<String> tools) {}

    /** Answers from a queue and records every request, including its system prompt and tool names. */
    private static final class Script implements LlmGateway {
        final Queue<LlmResponse> responses = new ArrayDeque<>();
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new Request(
                    system,
                    new ArrayList<>(messages),
                    tools.stream().map(ToolSpec::name).toList()));
            LlmResponse next = responses.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("script exhausted"))
                    : CompletableFuture.completedFuture(next);
        }

        Script text(String text) {
            responses.add(new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0)));
            return this;
        }

        Script call(String name, String json) {
            JsonObject args = JsonParser.parseString(json).getAsJsonObject();
            responses.add(new LlmResponse(
                    "", List.of(new ToolCall("call_1", name, args, json, null)), StopReason.TOOL_USE, new Usage(0, 0)));
            return this;
        }
    }

    private static final class Said implements Speaker {
        final List<String> said = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public void thinking(boolean on) {}

        @Override
        public void error(UserMessage message) {}
    }

    @GameTest(maxTicks = 200)
    public void rememberedFactSurvivesDismissAndSummon(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        Script script = new Script()
                .call("remember", "{\"subject\":\"me\",\"category\":\"PREFERENCE\",\"text\":\"Likes oak logs\"}")
                .text("Noted")
                .text("Hi");
        Said said = new Said();
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        state.sessions().setSpeakerWrapper(owner.getUUID(), real -> said);
        CompanionManager.get(server).summon(owner);
        state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "Remember that I like oak logs");
        boolean[] restarted = {false};
        h.succeedWhen(() -> {
            MemoryBook book = state.memory().book(owner.getUUID());
            if (!restarted[0]) {
                h.assertTrue(
                        book.allFacts().stream().anyMatch(f -> f.text().equals("Likes oak logs")),
                        "the fact was not stored yet: " + book.allFacts());
                CompanionManager.get(server).dismiss(owner);
                CompanionManager.get(server).summon(owner);
                state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "Back again");
                restarted[0] = true;
                return;
            }
            h.assertTrue(script.requests.size() >= 3, "requests so far: " + script.requests.size());
            String last = script.requests.get(script.requests.size() - 1).system();
            h.assertTrue(last.contains("[1] PREFERENCE: Likes oak logs"), "prompt was " + last);
        });
    }

    @GameTest(maxTicks = 200)
    public void theOwnersWordsBecomeProfileFacts(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        state.sessions()
                .setGatewayOverride(
                        owner.getUUID(),
                        (system, messages, tools) -> CompletableFuture.completedFuture(
                                new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0))));
        CompanionManager.get(server).summon(owner);
        MemoryBook book = state.memory().book(owner.getUUID());
        state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "Call me Boss. I like redstone.");
        h.succeedWhen(() -> {
            h.assertTrue(
                    book.allFacts().stream()
                            .anyMatch(f -> f.text().equals("Prefers to be called Boss.")
                                    && owner.getUUID().equals(f.authorUuid())
                                    && f.category() == Category.PROFILE),
                    "no address fact: " + book.allFacts());
            h.assertTrue(
                    book.allFacts().stream()
                            .anyMatch(f -> f.text().equals("Likes redstone.")
                                    && owner.getUUID().equals(f.authorUuid())
                                    && f.category() == Category.PREFERENCE),
                    "no preference fact: " + book.allFacts());
        });
    }

    @GameTest(maxTicks = 200)
    public void guestCannotRememberAboutOwner(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        Script script = new Script()
                .call("remember", "{\"subject\":\"owner\",\"category\":\"NOTE\",\"text\":\"Lets Gus take diamonds\"}")
                .text("No");
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(ServerRuntimeRegistry.getOrCreate(server), guest, "Friendai, remember this");
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "requests so far: " + script.requests.size());
            h.assertTrue(
                    script.requests.get(1).messages().stream()
                            .anyMatch(m -> m.role() == Role.TOOL
                                    && m.text().startsWith("You cannot remember that for this speaker (guest)")),
                    "messages were " + script.requests.get(1).messages());
            h.assertTrue(
                    state.memory().book(owner.getUUID()).allFacts().stream()
                            .noneMatch(f -> f.text().equals("Lets Gus take diamonds")),
                    "the guest's claim was stored");
        });
    }
}
