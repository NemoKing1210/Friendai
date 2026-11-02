package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Fact;
import dev.friendai.features.memory.Subject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SocialTopicsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static Fact fact(int id, Category category, String text, UUID author) {
        return new Fact(id, new Subject.Player(OWNER), category, text, author, "Owner", 0);
    }

    @Test
    void ownerStatementsBecomeAttributedCandidates() {
        List<TopicCandidate> candidates = SocialTopics.fromFacts(
                List.of(
                        fact(4, Category.PREFERENCE, "I like building with oak", OWNER),
                        fact(5, Category.PROMISE, "I will bring the diamonds", OWNER)),
                OWNER);

        assertEquals(2, candidates.size());
        assertEquals("fact:4", candidates.get(0).id());
        assertEquals(
                "You told me earlier: I like building with oak",
                candidates.get(0).text());
        assertEquals("fact:5", candidates.get(1).id());
    }

    @Test
    void inferredAndForeignFactsAreExcluded() {
        assertFalse(SocialTopics.eligible(fact(1, Category.NOTE, "a cave nearby", OWNER), OWNER));
        assertFalse(SocialTopics.eligible(fact(2, Category.PLACE, "home", OWNER), OWNER));
        assertFalse(SocialTopics.eligible(fact(3, Category.PREFERENCE, "I like stone", OTHER), OWNER));
        assertTrue(SocialTopics.eligible(fact(4, Category.PROFILE, "I am a builder", OWNER), OWNER));
    }

    @Test
    void aForgottenFactIsSimplyAbsent() {
        assertEquals(List.of(), SocialTopics.fromFacts(List.of(), OWNER));
        assertEquals(List.of(), SocialTopics.fromFacts(null, OWNER));
        assertTrue(SocialTopics.fromFacts(List.of(fact(9, Category.PREFERENCE, "  ", OWNER)), OWNER)
                .isEmpty());
    }

    @Test
    void nothingIsBuiltFromTheHappeningJournal() {
        // The builder takes facts only; happenings travel the separate HIGH news path and are consumer once there.
        assertEquals(List.of(), SocialTopics.fromFacts(List.of(), OWNER));
        assertFalse(SocialTopics.eligible(null, OWNER));
        assertFalse(SocialTopics.eligible(fact(1, Category.PREFERENCE, "x", OWNER), null));
    }
}
