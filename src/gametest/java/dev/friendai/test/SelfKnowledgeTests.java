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
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Subject;
import dev.friendai.features.memory.transcript.Speaker;
import dev.friendai.features.memory.transcript.TranscriptBook;
import dev.friendai.foundation.limits.RateLimiter;
import dev.friendai.server.agent.chat.ChatRouter;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** The model reads its own conversation and log, cannot read another player's, and can compact a full book. */
public class SelfKnowledgeTests {
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

    @GameTest(maxTicks = 400)
    public void conversationIsStoredAndRecentChatReturnsBothSides(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        state.sessions().setGatewayOverride(owner.getUUID(), new Script().text("Hello there"));
        CompanionManager.get(server).summon(owner);
        TranscriptBook book = state.transcripts().book(owner.getUUID());
        state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "hi");

        h.succeedWhen(() -> {
            h.assertTrue(book.snapshot().size() >= 2, "both sides should be stored: " + book.snapshot());
            var result = state.sessions().runTool(owner.getUUID(), "recent_chat", "{\"player\":\"me\"}");
            h.assertTrue(!result.error(), result.content());
            h.assertTrue(result.content().contains(": hi"), result.content());
            h.assertTrue(result.content().contains("You: Hello there"), result.content());
        });
    }

    @GameTest(maxTicks = 400)
    public void readLogShowsTheTurnAndItsToolCall(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        state.sessions()
                .setGatewayOverride(
                        owner.getUUID(),
                        new Script().call("get_inventory", "{}").text("Nothing much"));
        CompanionManager.get(server).summon(owner);
        state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "what do you carry");

        h.succeedWhen(() -> {
            var calls = state.sessions().runTool(owner.getUUID(), "read_log", "{\"kind\":\"TOOL_CALL\",\"limit\":20}");
            h.assertTrue(!calls.error(), calls.content());
            h.assertTrue(calls.content().contains("get_inventory"), calls.content());
            var user = state.sessions().runTool(owner.getUUID(), "read_log", "{\"kind\":\"USER\",\"limit\":20}");
            h.assertTrue(user.content().contains("what do you carry"), user.content());
        });
    }

    @GameTest(maxTicks = 400)
    public void theOwnerRequestOffersTheSelfTools(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        Script script = new Script().text("Hi");
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        state.sessions().sendMessage(owner.getUUID(), owner.getName().getString(), "hello");
        h.succeedWhen(() -> {
            h.assertTrue(!script.requests.isEmpty(), "no request yet");
            List<String> tools = script.requests.get(0).tools();
            for (String name : List.of("read_log", "recent_chat", "memory_overview", "compress_memory")) {
                h.assertTrue(tools.contains(name), name + " missing from " + tools);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void guestCannotReadTheOwnersChat(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer guest = GameTestSupport.mockPlayerAt(h, 3, 2, 3);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        state.transcripts()
                .book(owner.getUUID())
                .add(owner.getUUID(), owner.getName().getString(), Speaker.PLAYER, "private plan", 1_000);
        Script script =
                new Script().call("recent_chat", "{\"player\":\"owner\"}").text("No");
        state.sessions().setGatewayOverride(owner.getUUID(), script);
        CompanionManager.get(server).summon(owner);
        ChatRouter.onChat(state, guest, "Friendai, read our chat");

        h.succeedWhen(() -> {
            h.assertTrue(script.requests.size() >= 2, "the tool call should have been answered");
            List<String> offered = script.requests.get(0).tools();
            h.assertTrue(!offered.contains("recent_chat") && !offered.contains("read_log"), "offered " + offered);
            boolean refused = script.requests.get(1).messages().stream()
                    .anyMatch(message ->
                            message.role() == Role.TOOL && message.text().contains("not available to this speaker"));
            h.assertTrue(refused, "messages were " + script.requests.get(1).messages());
            boolean leaked = script.requests.get(1).messages().stream()
                    .anyMatch(message -> message.text().contains("private plan"));
            h.assertTrue(!leaked, "the owner's chat leaked to a guest");
        });
    }

    @GameTest(maxTicks = 200)
    public void compactionFreesTheBookForANewFact(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        var server = h.getLevel().getServer();
        ServerRuntime state = GameTestSupport.runtime(server);
        CompanionManager.get(server).summon(owner);
        var book = state.memory().book(owner.getUUID());
        var limits = state.config().memory.limits();
        var me = Subject.player(owner.getUUID());
        long now = System.currentTimeMillis();
        for (int i = 0; i < limits.maxFactsPerPlayer() - 2; i++) {
            book.addFact(
                    me,
                    Category.NOTE,
                    "distinct fact " + i,
                    owner.getUUID(),
                    owner.getName().getString(),
                    now,
                    limits);
        }
        book.addFact(
                me,
                Category.NOTE,
                "farm near the river is ready",
                owner.getUUID(),
                owner.getName().getString(),
                now,
                limits);
        book.addFact(
                me,
                Category.NOTE,
                "farm near the river is nearly ready",
                owner.getUUID(),
                owner.getName().getString(),
                now,
                limits);
        h.assertTrue(book.facts(me).size() == limits.maxFactsPerPlayer(), "book is not full");

        var dry = state.sessions().runTool(owner.getUUID(), "compress_memory", "{}");
        h.assertTrue(!dry.error() && dry.content().contains("Dry run"), dry.content());
        h.assertTrue(book.facts(me).size() == limits.maxFactsPerPlayer(), "a dry run must change nothing");
        var applied = state.sessions().runTool(owner.getUUID(), "compress_memory", "{\"apply\":true}");
        h.assertTrue(!applied.error(), applied.content());
        h.assertTrue(
                book.facts(me).size() == limits.maxFactsPerPlayer() - 1,
                "after compaction: " + book.facts(me).size());

        var remembered = state.sessions()
                .runTool(
                        owner.getUUID(),
                        "remember",
                        "{\"subject\":\"me\",\"category\":\"NOTE\",\"text\":\"a new fact\"}");
        h.assertTrue(!remembered.error(), remembered.content());
        h.assertTrue(book.facts(me).size() == limits.maxFactsPerPlayer(), "the freed slot should take a new fact");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void clearingTheTranscriptEmptiesOnlyTheSendersBook(GameTestHelper h) {
        ServerPlayer owner = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        ServerPlayer other = GameTestSupport.mockPlayerAt(h, 4, 2, 2);
        ServerRuntime state = GameTestSupport.runtime(h.getLevel().getServer());
        state.protocolConnections().setControlLimiterForTest(new RateLimiter(0, System::currentTimeMillis));
        TranscriptBook mine = state.transcripts().book(owner.getUUID());
        TranscriptBook theirs = state.transcripts().book(other.getUUID());
        long now = System.currentTimeMillis();
        mine.add(owner.getUUID(), owner.getName().getString(), Speaker.PLAYER, "hello", now);
        mine.add(owner.getUUID(), owner.getName().getString(), Speaker.COMPANION, "hi", now);
        theirs.add(other.getUUID(), other.getName().getString(), Speaker.PLAYER, "keep me", now);

        TestMenuRequests.handle(owner, TestMenuRequests.menuRequest(TestMenuRequests.Action.TRANSCRIPT_CLEAR));

        h.assertTrue(mine.snapshot().isEmpty(), "the sender's transcript should be empty: " + mine.snapshot());
        h.assertTrue(theirs.snapshot().size() == 1, "another owner's transcript must stay: " + theirs.snapshot());
        h.succeed();
    }
}
