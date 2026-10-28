package dev.friendai.agent.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import dev.friendai.agent.llm.model.ToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationMemoryTest {
    private static ToolCall call(String id) {
        return new ToolCall(id, "tool", new JsonObject(), "{}", null);
    }

    @Test
    void keepsWithinLimit() {
        ConversationMemory memory = new ConversationMemory(30);
        for (int i = 0; i < 20; i++) {
            memory.add(Message.user("u" + i));
            memory.add(Message.assistant("a" + i, List.of()));
        }
        List<Message> snap = memory.snapshot();
        assertTrue(snap.size() <= 30);
        assertEquals("a19", snap.get(snap.size() - 1).text());
    }

    @Test
    void trimNeverStartsWithToolResultOrDanglingCall() {
        for (int limit = 1; limit <= 14; limit++) {
            ConversationMemory memory = new ConversationMemory(limit);
            List<Message> script = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                script.add(Message.user("u" + round));
                script.add(Message.assistant("", List.of(call("a" + round), call("b" + round))));
                script.add(Message.toolResult("a" + round, "tool", "ok"));
                script.add(Message.toolResult("b" + round, "tool", "ok"));
                script.add(Message.assistant("done" + round, List.of()));
            }
            for (Message m : script) {
                memory.add(m);
                assertWellFormed(memory.snapshot(), limit);
            }
        }
    }

    private static void assertWellFormed(List<Message> snap, int limit) {
        if (snap.isEmpty()) {
            return;
        }
        assertEquals(Role.USER, snap.get(0).role(), "limit " + limit + ": history must start with USER");
        for (int i = 0; i < snap.size(); i++) {
            Message m = snap.get(i);
            if (m.role() == Role.ASSISTANT
                    && !m.toolCalls().isEmpty()
                    && i + m.toolCalls().size() < snap.size() + 1) {
                Set<String> expected = new HashSet<>();
                m.toolCalls().forEach(c -> expected.add(c.id()));
                Set<String> found = new HashSet<>();
                for (int j = i + 1; j < snap.size() && snap.get(j).role() == Role.TOOL; j++) {
                    found.add(snap.get(j).toolCallId());
                }
                // A call group at the very tail may still be waiting for results; anything earlier must be complete.
                boolean atTail = i + 1 + found.size() == snap.size();
                assertTrue(
                        atTail || found.equals(expected), "limit " + limit + ": call group at " + i + " lost results");
            }
        }
    }

    @Test
    void longTurnNeverLosesItsOwnUserMessage() {
        ConversationMemory memory = new ConversationMemory(6);
        memory.add(Message.user("mine everything"));
        for (int i = 0; i < 5; i++) {
            memory.add(Message.assistant("", List.of(call("c" + i))));
            memory.add(Message.toolResult("c" + i, "tool", "ok"));
        }
        List<Message> snap = memory.snapshot();
        assertEquals(11, snap.size());
        assertEquals(Role.USER, snap.get(0).role());
        assertEquals("mine everything", snap.get(0).text());
    }

    @Test
    void clearEmptiesMemory() {
        ConversationMemory memory = new ConversationMemory(10);
        memory.add(Message.user("hi"));
        memory.clear();
        assertTrue(memory.snapshot().isEmpty());
    }
}
