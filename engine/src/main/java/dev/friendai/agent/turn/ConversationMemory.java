package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.Role;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded in-memory history. Trimming drops whole leading turns (a USER message and everything up to the
 * next one), so the history never starts with a tool result or an assistant tool call whose results were
 * cut off — providers reject such history. The turn in progress is never dropped, so a long tool loop can
 * temporarily exceed the limit.
 */
public final class ConversationMemory {
    private final int maxMessages;
    private final List<Message> messages = new ArrayList<>();

    public ConversationMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void add(Message m) {
        messages.add(m);
        while (messages.size() > maxMessages) {
            int nextTurn = nextUserIndexAfterHead();
            if (nextTurn < 0) {
                break;
            }
            messages.subList(0, nextTurn).clear();
        }
        // History that never started with a USER message (it cannot be built through the brain) is repaired.
        while (!messages.isEmpty() && messages.get(0).role() != Role.USER && nextUserIndexAfterHead() > 0) {
            messages.remove(0);
        }
    }

    private int nextUserIndexAfterHead() {
        for (int i = 1; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.USER) {
                return i;
            }
        }
        return -1;
    }

    public List<Message> snapshot() {
        return new ArrayList<>(messages);
    }

    public void clear() {
        messages.clear();
    }
}
