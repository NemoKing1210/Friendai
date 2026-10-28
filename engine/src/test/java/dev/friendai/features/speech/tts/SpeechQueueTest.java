package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SpeechQueueTest {
    @Test
    void keepsTheNewestEightInOrder() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        for (int i = 0; i < 10; i++) {
            queue.add("u" + i);
        }
        assertEquals(8, queue.size());
        for (int i = 2; i < 10; i++) {
            assertEquals("u" + i, queue.poll());
        }
    }

    @Test
    void clearEmptiesTheQueue() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.add("one");
        queue.add("two");
        queue.clear();
        assertEquals(0, queue.size());
        assertNull(queue.poll());
    }

    @Test
    void pollOnEmptyReturnsNull() {
        assertNull(new SpeechQueue<String>().poll());
    }

    @Test
    void keepsEntriesOfDifferentPlansInOrder() {
        SpeechQueue<String> queue = new SpeechQueue<>();
        queue.add("first");
        queue.add("second");
        assertEquals("first", queue.poll());
        assertEquals("second", queue.poll());
    }
}
