package dev.friendai.features.speech.tts;

import java.util.ArrayDeque;
import java.util.Deque;

/** A bounded FIFO of utterances waiting to be synthesized and played; the oldest is dropped when full. */
public final class SpeechQueue<T> {
    public static final int CAPACITY = 8;

    private final Deque<T> items = new ArrayDeque<>();

    public void add(T item) {
        if (items.size() >= CAPACITY) {
            items.removeFirst();
        }
        items.addLast(item);
    }

    public T poll() {
        return items.pollFirst();
    }

    public void clear() {
        items.clear();
    }

    public int size() {
        return items.size();
    }
}
