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
import dev.friendai.agent.turn.BrainEvent;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.turn.Speaker;
import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionManager;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.server.agent.chat.ChatRouter;
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

/** A nearby guest talks to someone else's companion: routing, guest tool limits and trust. */
public class GuestTests {
    private record Request(List<Message> messages, List<String> tools) {}

    /** Answers from a queue and records every request, including the tool names it offered. */
    private static final class Script implements LlmGateway {
        final Queue<LlmResponse> responses = new ArrayDeque<>();
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
            requests.add(new Request(
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

    private static Message lastUser(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                return messages.get(i);
            }
        }
        throw new AssertionError("no user message in " + messages);
    }

    @GameTest(maxTicks = 100)
    public void guestReachesNearbyCompanion(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        var brains = FriendaiServerState.get(server).sessions();
        Script script = new Script().text("Hello");
        Said said = new Said();
        brains.setGatewayOverride(owner.getUUID(), script);
        brains.setSpeakerWrapper(owner.getUUID(), real -> said);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(ServerRuntimeRegistry.getOrCreate(server), guest, "Friendai, hi");
        h.succeedWhen(() -> {
            h.assertTrue(said.said.contains("Hello"), "said " + said.said);
            h.assertTrue(!script.requests.isEmpty(), "no LLM request yet");
            Request first = script.requests.get(0);
            h.assertTrue(
                    lastUser(first.messages()).text().contains("(guest)]: "),
                    "user line was " + lastUser(first.messages()).text());
            h.assertTrue(
                    first.tools().equals(List.of("look_around", "remember", "forget", "recall")),
                    "tools were " + first.tools());
        });
    }

    @GameTest(maxTicks = 3600)
    public void guestCannotAct(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        var brains = FriendaiServerState.get(server).sessions();
        Script script = new Script().call("stay", "{}").text("No");
        brains.setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(ServerRuntimeRegistry.getOrCreate(server), guest, "Friendai, stay there");
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "requests so far: " + script.requests.size());
            h.assertTrue(
                    script.requests.get(1).messages().stream()
                            .anyMatch(m -> m.role() == Role.TOOL
                                    && m.text().contains("not available to this speaker (guest)")),
                    "messages were " + script.requests.get(1).messages());
        });
    }

    @GameTest(maxTicks = 3600)
    public void trustedGuestCanAct(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        state.memory()
                .book(owner.getUUID())
                .setTrusted(guest.getUUID(), guest.getName().getString(), true, 0);
        Script script = new Script().call("stay", "{}").text("Okay");
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(ServerRuntimeRegistry.getOrCreate(server), guest, "Friendai, stay there");
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "requests so far: " + script.requests.size());
            h.assertTrue(
                    script.requests.get(1).messages().stream()
                            .anyMatch(m -> m.role() == Role.TOOL && m.text().contains("started")),
                    "messages were " + script.requests.get(1).messages());
        });
    }

    /** A guest's words must not teach the companion its owner's language; the owner's own words do. */
    @GameTest(maxTicks = 1200)
    public void onlyTheOwnersOwnWordsSetTheirLanguage(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        FriendaiServerState state = FriendaiServerState.get(server);
        var brains = state.sessions();
        brains.setGatewayOverride(
                owner.getUUID(),
                (system, messages, tools) -> CompletableFuture.completedFuture(
                        new LlmResponse("[silent]", List.of(), StopReason.END, new Usage(0, 0))));
        CompanionManager.get(server).summon(owner);
        var book = state.memory().book(owner.getUUID());
        String russian = "Привет! Расскажи мне, пожалуйста, как построить дом из дерева.";
        brains.post(
                owner.getUUID(),
                new BrainEvent.PlayerSaid(
                        guest.getUUID(),
                        guest.getName().getString(),
                        "Call me Boss. I like redstone.",
                        BrainEvent.Source.CHAT));
        h.assertTrue(
                book.allFacts().stream().noneMatch(f -> f.text().startsWith("Prefers to be called")),
                "a guest set the owner's address: " + book.allFacts());
        h.assertTrue(
                book.allFacts().stream().noneMatch(f -> f.text().equals("Likes redstone.")),
                "a guest set the owner's preference: " + book.allFacts());
        brains.post(
                owner.getUUID(),
                new BrainEvent.PlayerSaid(
                        guest.getUUID(), guest.getName().getString(), russian, BrainEvent.Source.CHAT));
        h.assertTrue(book.ownerLanguage(owner.getUUID()).isEmpty(), "a guest set the owner's language");
        brains.post(
                owner.getUUID(),
                new BrainEvent.PlayerSaid(
                        owner.getUUID(), owner.getName().getString(), russian, BrainEvent.Source.CHAT));
        h.assertTrue(
                book.ownerLanguage(owner.getUUID()).equals(java.util.Optional.of("Russian")),
                "the owner's own message did not set the language: " + book.ownerLanguage(owner.getUUID()));
        h.assertTrue(
                book.allFacts().stream().anyMatch(f -> f.text().equals("Writes in Russian.")),
                "the learned language was not mirrored into the profile: " + book.allFacts());
        // A two-word reply in another language is not evidence either: the stored language keeps saying Russian.
        brains.post(
                owner.getUUID(),
                new BrainEvent.PlayerSaid(
                        owner.getUUID(), owner.getName().getString(), "ok thanks", BrainEvent.Source.CHAT));
        h.succeedWhen(() -> {
            h.assertTrue(
                    book.ownerLanguage(owner.getUUID()).equals(java.util.Optional.of("Russian")),
                    "a short English reply flipped the language: " + book.ownerLanguage(owner.getUUID()));
            String prompt = brains.currentPrompt(owner.getUUID()).orElse("");
            h.assertTrue(
                    prompt.contains("usually writes in Russian"), "the prompt lost the learned language: " + prompt);
        });
    }
}
