package dev.friendai.agent.turn;

import static dev.friendai.testutil.FakeGateway.call;
import static dev.friendai.testutil.FakeGateway.calls;
import static dev.friendai.testutil.FakeGateway.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.testutil.FakeGateway;
import dev.friendai.testutil.FakeToolExecutor;
import dev.friendai.testutil.ManualScheduler;
import dev.friendai.testutil.RecordingSpeaker;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A model that repeats one failing call cannot spend a turn on it: the call runs at most twice, every later copy
 * reuses the recorded error, and each call still gets exactly one ordered tool message.
 */
class CompanionBrainRepeatedCallTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private FakeGateway gateway;
    private FakeToolExecutor tools;
    private RecordingSpeaker speaker;
    private CompanionBrain brain;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        tools = new FakeToolExecutor().offers("mine_blocks");
        speaker = new RecordingSpeaker();
        brain = new CompanionBrain(
                () -> new Persona("Friendai", "friendly", "auto"),
                () -> new BrainSettings(8, 30, 0, 0),
                () -> gateway,
                tools,
                () -> "state",
                speaker,
                new ManualScheduler(),
                AccessResolver.of(OWNER, new HashSet<UUID>()::contains));
    }

    private void failMineBlocks(int times) {
        java.util.Deque<ToolResult> scripted = tools.results.computeIfAbsent("mine_blocks", name -> new ArrayDeque<>());
        for (int i = 0; i < times; i++) {
            scripted.add(ToolResult.error("no minecraft:stone in reach"));
        }
    }

    private void say(String text) {
        brain.post(new BrainEvent.PlayerSaid(OWNER, "Steve", text, BrainEvent.Source.CHAT));
    }

    private static List<Message> toolMessages(FakeGateway.Request request) {
        return request.messages().stream()
                .filter(message -> message.role() == Role.TOOL)
                .toList();
    }

    @Test
    void anIdenticalFailingCallRunsTwiceAndItsErrorIsReused() {
        failMineBlocks(3);
        gateway.enqueue(calls("", call("c1", "mine_blocks"), call("c2", "mine_blocks"), call("c3", "mine_blocks")));
        gateway.enqueue(text("I cannot break that from here."));

        say("mine some stone");

        assertEquals(List.of("mine_blocks", "mine_blocks"), tools.executed, "the third identical call is not run");
        List<Message> results = toolMessages(gateway.requests.get(1));
        assertEquals(3, results.size(), "every call keeps its own ordered tool message");
        assertEquals("no minecraft:stone in reach", results.get(0).text());
        assertTrue(
                results.get(2).text().contains("not run again"), results.get(2).text());
        assertTrue(
                results.get(2).text().contains("no minecraft:stone in reach"),
                results.get(2).text());
        assertEquals(2, gateway.requests.size());
    }

    @Test
    void aChangedArgumentStillExecutes() {
        failMineBlocks(3);
        ToolCall stone = new ToolCall(
                "c1",
                "mine_blocks",
                JsonParser.parseString("{\"type\":\"minecraft:stone\"}").getAsJsonObject(),
                "{\"type\":\"minecraft:stone\"}",
                null);
        ToolCall iron = new ToolCall(
                "c2",
                "mine_blocks",
                JsonParser.parseString("{\"type\":\"minecraft:iron_ore\"}").getAsJsonObject(),
                "{\"type\":\"minecraft:iron_ore\"}",
                null);
        ToolCall again = new ToolCall(
                "c3",
                "mine_blocks",
                JsonParser.parseString("{\"type\":\"minecraft:stone\"}").getAsJsonObject(),
                "{\"type\":\"minecraft:stone\"}",
                null);
        gateway.enqueue(calls("", stone, iron, again));
        gateway.enqueue(text("Nothing found."));

        say("mine some stone");

        assertEquals(List.of("mine_blocks", "mine_blocks", "mine_blocks"), tools.executed);
    }

    @Test
    void aNewTurnForgetsTheEarlierFailures() {
        failMineBlocks(2);
        gateway.enqueue(calls("", call("c1", "mine_blocks"), call("c2", "mine_blocks")));
        gateway.enqueue(text("It failed."));
        say("mine some stone");
        assertEquals(2, tools.executed.size());

        gateway.enqueue(calls("", call("c3", "mine_blocks")));
        gateway.enqueue(text("Trying again."));
        say("try the stone again");

        assertEquals(
                List.of("mine_blocks", "mine_blocks", "mine_blocks"),
                tools.executed,
                "a new turn starts with a clean ledger");
    }
}
