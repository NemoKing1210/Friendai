package dev.friedai.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friedai.brain.LlmGateway;
import dev.friedai.brain.Speaker;
import dev.friedai.chat.ChatRouter;
import dev.friedai.core.FriedaiServerState;
import dev.friedai.entity.CompanionManager;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.Role;
import dev.friedai.llm.model.StopReason;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.llm.model.Usage;
import dev.friedai.text.UserMessage;
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
                    new ArrayList<>(messages), tools.stream().map(ToolSpec::name).toList()));
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
        var brains = FriedaiServerState.get(server).brains();
        Script script = new Script().text("Hello");
        Said said = new Said();
        brains.setGatewayOverride(owner.getUUID(), script);
        brains.setSpeakerWrapper(owner.getUUID(), real -> said);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(guest, "Friendai, hi");
        h.succeedWhen(() -> {
            h.assertTrue(said.said.contains("Hello"), "said " + said.said);
            h.assertTrue(!script.requests.isEmpty(), "no LLM request yet");
            Request first = script.requests.get(0);
            h.assertTrue(lastUser(first.messages()).text().contains("(guest)]: "),
                    "user line was " + lastUser(first.messages()).text());
            h.assertTrue(first.tools().equals(List.of("look_around")), "tools were " + first.tools());
        });
    }

    @GameTest(maxTicks = 3600)
    public void guestCannotAct(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        var brains = FriedaiServerState.get(server).brains();
        Script script = new Script().call("stay", "{}").text("No");
        brains.setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(guest, "Friendai, stay there");
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
        FriedaiServerState state = FriedaiServerState.get(server);
        state.memory()
                .book(owner.getUUID())
                .setTrusted(guest.getUUID(), guest.getName().getString(), true, 0);
        Script script = new Script().call("stay", "{}").text("Okay");
        state.brains().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(guest, "Friendai, stay there");
        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "requests so far: " + script.requests.size());
            h.assertTrue(
                    script.requests.get(1).messages().stream()
                            .anyMatch(m -> m.role() == Role.TOOL && m.text().contains("started")),
                    "messages were " + script.requests.get(1).messages());
        });
    }
}
