package dev.friendai.voice.tts;

import java.util.ArrayDeque;
import java.util.Deque;

/** A bounded FIFO of utterances waiting to be synthesized and played; the oldest is dropped when full. */
public final class SpeechQueue {
    public static final int CAPACITY = 8;

    private final Deque<String> items = new ArrayDeque<>();

    public void add(String text) {
        if (items.size() >= CAPACITY) {
            items.removeFirst();
        }
        items.addLast(text);
    }

    public String poll() {
        return items.pollFirst();
    }

    public void clear() {
        items.clear();
    }

    public int size() {
        return items.size();
    }
}
