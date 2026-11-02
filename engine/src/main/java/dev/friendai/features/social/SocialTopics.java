package dev.friendai.features.social;

import dev.friendai.features.memory.AutoProfile;
import dev.friendai.features.memory.Category;
import dev.friendai.features.memory.Fact;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns remembered facts into ambient topic candidates without letting a recorded claim become a witnessed one.
 *
 * <p>Only an owner's own durable statement about themselves qualifies: a {@link Category#PROFILE},
 * {@link Category#PREFERENCE} or {@link Category#PROMISE} fact whose subject and author are the owner. The rendered
 * line names the source ("You told me earlier: …") so the model cannot present it as something the companion saw.
 * Inferred results, facts about other players and perishable place or note records are excluded, and no candidate is
 * built from the happening journal: happenings already travel the separate HIGH news path and must not be repeated
 * as an ambient recollection.
 *
 * <p>Pure: the adapter supplies the facts, the fact id is the stable identity, and the text is not trimmed into a
 * different claim — an over-long rendering is dropped by {@link TopicCandidates}.
 */
public final class SocialTopics {
    private SocialTopics() {}

    /** The candidates the owner's own statements support, oldest record first. */
    public static List<TopicCandidate> fromFacts(List<Fact> facts, UUID owner) {
        if (facts == null || facts.isEmpty() || owner == null) {
            return List.of();
        }
        List<TopicCandidate> candidates = new ArrayList<>(facts.size());
        for (Fact fact : facts) {
            if (!eligible(fact, owner)) {
                continue;
            }
            String text = fact.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            candidates.add(new TopicCandidate("fact:" + fact.id(), "You told me earlier: " + text.strip()));
        }
        return candidates;
    }

    /** Whether the fact is an attributable, durable, owner-only statement about the owner. */
    public static boolean eligible(Fact fact, UUID owner) {
        if (fact == null || owner == null || !owner.equals(fact.authorUuid())) {
            return false;
        }
        Category category = fact.category();
        return (category == Category.PROFILE || category == Category.PREFERENCE || category == Category.PROMISE)
                && !AutoProfile.isLanguageFact(fact.text());
    }
}
