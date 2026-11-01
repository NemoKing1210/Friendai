package dev.friendai.features.social;

import java.util.ArrayDeque;

/**
 * The last few subjects the companion raised, so it does not open the same one twice.
 *
 * <p>Bounded and pure: the oldest subject leaves when the cap is reached, so the memory costs a few short strings
 * per owner and nothing else. Comparison ignores case and runs of whitespace, because the same opener can come back
 * from the bank with different spacing and must still count as a repeat.
 */
public final class TopicRing {
    /** Subjects remembered per owner: the last twelve raised ones. */
    public static final int CAPACITY = 12;

    private final ArrayDeque<String> raised = new ArrayDeque<>(CAPACITY);

    /** Whether the subject may be raised: it is not one of the remembered ones. */
    public boolean allows(String topic) {
        return !raised.contains(key(topic));
    }

    /** Remembers a subject as raised; a repeat of one still in the ring keeps its place instead of filling a slot. */
    public void remember(String topic) {
        String key = key(topic);
        if (raised.contains(key)) {
            return;
        }
        raised.addLast(key);
        while (raised.size() > CAPACITY) {
            raised.removeFirst();
        }
    }

    public int size() {
        return raised.size();
    }

    /** The comparison form: no leading or trailing space, one space between words, lower case. */
    private static String key(String topic) {
        if (topic == null) {
            return "";
        }
        StringBuilder key = new StringBuilder(topic.length());
        boolean pendingSpace = false;
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = !key.isEmpty();
                continue;
            }
            if (pendingSpace) {
                key.append(' ');
                pendingSpace = false;
            }
            key.append(Character.toLowerCase(c));
        }
        return key.toString();
    }
}
