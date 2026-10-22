package dev.friedai.brain;

import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.Role;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded in-memory history. Trimming drops whole leading turns so the history never starts with a
 * tool result or an assistant tool call whose results were cut off (providers reject such history).
 */
public final class ConversationMemory {
    private final int maxMessages;
    private final Deque<Message> messages = new ArrayDeque<>();

    public ConversationMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void add(Message m) {
        messages.addLast(m);
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
        while (!messages.isEmpty() && messages.peekFirst().role() != Role.USER) {
            messages.removeFirst();
        }
    }

    public List<Message> snapshot() {
        return new ArrayList<>(messages);
    }

    public void clear() {
        messages.clear();
    }
}
