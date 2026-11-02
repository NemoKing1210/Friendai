package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SocialMemorySaveTest {
    @Test
    void encodeDecodeKeepsRaisedOrderAndTasteWeights() {
        List<String> raised = List.of("first subject", "second subject", "third subject");
        Map<String, Integer> taste = new LinkedHashMap<>();
        taste.put("second subject", 3);
        taste.put("first subject", 1);
        String encoded = new SocialMemorySave(raised, taste).encode();
        SocialMemorySave decoded = SocialMemorySave.decode(encoded).orElseThrow();
        assertEquals(raised, decoded.raised());
        assertEquals(taste, decoded.taste());
    }

    @Test
    void unknownFieldsAreIgnoredRatherThanRejected() {
        String saved = "{\"raised\":[\"a subject\"],\"taste\":{\"a subject\":2},\"future\":{\"x\":[1,2]}}";
        SocialMemorySave decoded = SocialMemorySave.decode(saved).orElseThrow();
        assertEquals(List.of("a subject"), decoded.raised());
        assertEquals(Map.of("a subject", 2), decoded.taste());
    }

    @Test
    void brokenOrMissingTextIsEmptyInsteadOfThrowing() {
        String oversized = "x".repeat(SocialMemorySave.MAX_INPUT + 1);
        assertTrue(SocialMemorySave.decode(null).isEmpty());
        assertTrue(SocialMemorySave.decode("").isEmpty());
        assertTrue(SocialMemorySave.decode("{").isEmpty());
        assertTrue(SocialMemorySave.decode("[]").isEmpty());
        assertTrue(SocialMemorySave.decode("{\"raised\":[1]}").isEmpty());
        assertTrue(SocialMemorySave.decode("{\"raised\":[\"   \"]}").isEmpty());
        assertTrue(SocialMemorySave.decode("{\"taste\":{\"a\":\"heavy\"}}").isEmpty());
        assertTrue(SocialMemorySave.decode(oversized).isEmpty());
    }

    @Test
    void onlyTheBoundedNumberOfEntriesAndKnownValuesDecode() {
        List<String> tooManyRaised = new ArrayList<>();
        for (int i = 0; i <= SocialMemorySave.MAX_RAISED; i++) {
            tooManyRaised.add("subject " + i);
        }
        String overRing = encode(tooManyRaised, Map.of());
        String repeated = encode(List.of("twice", "twice"), Map.of());

        Map<String, Integer> tooManyWeights = new LinkedHashMap<>();
        for (int i = 0; i <= SocialMemorySave.MAX_TASTE; i++) {
            tooManyWeights.put("liked " + i, 1);
        }
        String overTaste = encode(List.of(), tooManyWeights);
        String unanswered = encode(List.of(), Map.of("never answered", 0));
        String tooHeavy = encode(List.of(), Map.of("loved", SocialMemorySave.MAX_WEIGHT + 1));
        String tooLong = encode(List.of("x".repeat(SocialMemorySave.MAX_TOPIC_CHARS + 1)), Map.of());

        assertTrue(SocialMemorySave.decode(overRing).isEmpty());
        assertTrue(SocialMemorySave.decode(repeated).isEmpty());
        assertTrue(SocialMemorySave.decode(overTaste).isEmpty());
        assertTrue(SocialMemorySave.decode(unanswered).isEmpty());
        assertTrue(SocialMemorySave.decode(tooHeavy).isEmpty());
        assertTrue(SocialMemorySave.decode(tooLong).isEmpty());
    }

    @Test
    void aRestoredRingAndTasteBehaveLikeTheLiveOnes() {
        TopicRing ring = new TopicRing();
        TopicTaste taste = new TopicTaste();
        ring.remember("Mining deeper next time?");
        ring.remember("The trees by the river");
        taste.answered("Mining deeper next time?");
        taste.answered("Mining deeper next time?");

        String encoded = new SocialMemorySave(ring.entries(), taste.weights()).encode();
        SocialMemorySave decoded = SocialMemorySave.decode(encoded).orElseThrow();
        TopicRing restoredRing = TopicRing.of(decoded.raised());
        TopicTaste restoredTaste = TopicTaste.of(decoded.taste());

        assertFalse(restoredRing.allows("  mining deeper NEXT time? "));
        assertEquals(2, restoredRing.size());
        assertEquals(1, restoredTaste.weight("mining deeper next time?"));
        String best = restoredTaste.best(List.of("The trees by the river", "Mining deeper next time?"));
        assertEquals("Mining deeper next time?", best);
        assertNull(restoredTaste.best(List.of("The trees by the river")));
    }

    private static String encode(List<String> raised, Map<String, Integer> taste) {
        return new SocialMemorySave(raised, taste).encode();
    }
}
