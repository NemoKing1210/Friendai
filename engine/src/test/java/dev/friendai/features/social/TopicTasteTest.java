package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TopicTasteTest {
    @Test
    void twoAnswersRaiseTheWeightByOne() {
        TopicTaste taste = new TopicTaste();
        taste.answered("fishing");
        assertEquals(0, taste.weight("fishing"), "one answer is not a preference yet");
        taste.answered("fishing");
        assertEquals(1, taste.weight("fishing"));
    }

    @Test
    void anIgnoredSubjectLosesWeightAndItsStreak() {
        TopicTaste taste = new TopicTaste();
        taste.answered("fishing");
        taste.answered("fishing");
        taste.ignored("fishing");
        assertEquals(0, taste.weight("fishing"));
        taste.answered("fishing");
        assertEquals(0, taste.weight("fishing"), "the streak restarted after the silence");
    }

    @Test
    void theBestTopicIsTheHeaviestAndNullWhenAllAreEqual() {
        TopicTaste taste = new TopicTaste();
        taste.answered("mining");
        taste.answered("mining");
        assertEquals("mining", taste.best(List.of("fishing", "mining")));
        assertNull(taste.best(List.of("fishing", "farming")));
    }

    @Test
    void theMapIsBounded() {
        TopicTaste taste = new TopicTaste();
        for (int i = 0; i < TopicTaste.MAX_TOPICS + 20; i++) {
            taste.answered("topic " + i);
            taste.answered("topic " + i);
        }
        assertTrue(taste.size() <= TopicTaste.MAX_TOPICS, "size=" + taste.size());
    }

    @Test
    void anIgnoredSubjectLosesOnePointAndMustEarnItsWeightBack() {
        TopicTaste taste = new TopicTaste();
        for (int i = 0; i < 4; i++) {
            taste.answered("fishing");
        }
        assertEquals(2, taste.weight("fishing"));

        taste.ignored("fishing");
        assertEquals(1, taste.weight("fishing"), "one silence costs one point, not the whole weight");

        taste.answered("fishing");
        assertEquals(1, taste.weight("fishing"), "one answer after a silence is not enough");
        taste.answered("fishing");
        assertEquals(2, taste.weight("fishing"), "two fresh answers earn the point back");
    }
}
