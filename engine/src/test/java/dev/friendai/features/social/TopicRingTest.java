package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TopicRingTest {
    @Test
    void aRememberedTopicIsRefusedUntilItLeavesTheRing() {
        TopicRing ring = new TopicRing();
        assertTrue(ring.allows("What did you get up to while I was away?"));
        ring.remember("What did you get up to while I was away?");
        assertEquals(1, ring.size());
        assertFalse(ring.allows("What did you get up to while I was away?"));
        assertTrue(ring.allows("The trees by the river are taller than I remembered."));
    }

    @Test
    void comparisonIgnoresCaseAndWhitespace() {
        TopicRing ring = new TopicRing();
        ring.remember("What did you get up to while I was away?");
        assertFalse(ring.allows("  what did you get up to WHILE I was away?  "));
        assertFalse(ring.allows("What\t did   you get up to while I was away?"));
    }

    @Test
    void rememberingTheSameTopicTwiceDoesNotFillTheRing() {
        TopicRing ring = new TopicRing();
        ring.remember("a subject");
        ring.remember("A  SUBJECT");
        assertEquals(1, ring.size());
        assertFalse(ring.allows("a subject"));
    }

    @Test
    void theOldestTopicLeavesWhenTheThirteenthArrives() {
        TopicRing ring = new TopicRing();
        for (int i = 0; i < TopicRing.CAPACITY; i++) {
            String topic = "topic " + i;
            assertTrue(ring.allows(topic), topic + " was refused before the ring held it");
            ring.remember(topic);
        }
        assertEquals(TopicRing.CAPACITY, ring.size());
        assertFalse(ring.allows("topic 0"));

        ring.remember("topic 12");
        assertEquals(TopicRing.CAPACITY, ring.size());
        assertTrue(ring.allows("topic 0"), "the oldest topic did not leave the ring");
        assertFalse(ring.allows("topic 1"), "a topic inside the ring was allowed");
        assertFalse(ring.allows("topic 12"), "the newest topic was not remembered");
    }

    @Test
    void aFreshRingAllowsEverythingAndIsEmpty() {
        TopicRing ring = new TopicRing();
        assertEquals(0, ring.size());
        assertTrue(ring.allows("anything at all"));
        assertTrue(ring.allows(null));
    }
}
