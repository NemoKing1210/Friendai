package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FactProvenanceTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final long NOW = 3_600_000;
    private static final long DAY = 86_400_000L;

    private static Fact fact(int id, Category category, String text, UUID author, long createdAt) {
        return new Fact(id, Subject.player(O), category, text, author, author == null ? null : "author", createdAt);
    }

    @Test
    void aGoalTheWorldCheckedIsVerified() {
        String text =
                GoalOutcomeFact.text("Collect 32 oak logs", GoalOutcomeFact.End.VERIFIED, "+32 minecraft:oak_log", 160);
        assertEquals(FactProvenance.Source.VERIFIED, FactProvenance.source(fact(1, Category.NOTE, text, T, NOW), O));
    }

    @Test
    void anUncheckedGoalOutcomeStaysInferred() {
        String unchecked = GoalOutcomeFact.text("Make a friend", GoalOutcomeFact.End.UNVERIFIED, null, 160);
        assertEquals(
                FactProvenance.Source.INFERRED, FactProvenance.source(fact(1, Category.NOTE, unchecked, T, NOW), O));
        String abandoned =
                GoalOutcomeFact.text("Build a tower", GoalOutcomeFact.End.ABANDONED, "+12 minecraft:stone", 160);
        assertEquals(
                FactProvenance.Source.INFERRED, FactProvenance.source(fact(2, Category.NOTE, abandoned, T, NOW), O));
    }

    @Test
    void anOwnerProfilePreferenceOrPromiseIsToldByTheOwner() {
        for (Category category : List.of(Category.PROFILE, Category.PREFERENCE, Category.PROMISE)) {
            assertEquals(
                    FactProvenance.Source.OWNER,
                    FactProvenance.source(fact(1, category, "Likes redstone", O, NOW), O),
                    category.name());
        }
    }

    @Test
    void aPreferenceFromSomeoneElseAndEveryPerishableClaimIsInferred() {
        assertEquals(
                FactProvenance.Source.INFERRED,
                FactProvenance.source(fact(1, Category.PREFERENCE, "Likes boats", T, NOW), O));
        assertEquals(
                FactProvenance.Source.INFERRED,
                FactProvenance.source(fact(2, Category.PLACE, "Base at 1 2 3", O, NOW), O));
        assertEquals(
                FactProvenance.Source.INFERRED,
                FactProvenance.source(fact(3, Category.NOTE, "Village to the north", O, NOW), O));
        assertEquals(
                FactProvenance.Source.INFERRED,
                FactProvenance.source(fact(4, Category.PROFILE, "Gus likes cats", null, NOW), O));
    }

    @Test
    void onlyAPlaceOrNoteCanGoStale() {
        assertTrue(FactProvenance.expired(fact(1, Category.PLACE, "Base", O, NOW - 30 * DAY), NOW));
        assertTrue(FactProvenance.expired(fact(2, Category.NOTE, "Village", T, NOW - 45 * DAY), NOW));
        assertFalse(FactProvenance.expired(fact(3, Category.PLACE, "Base", O, NOW - 29 * DAY), NOW));
        assertFalse(FactProvenance.expired(fact(4, Category.NOTE, "Village", T, NOW + DAY), NOW));
        assertFalse(FactProvenance.expired(fact(5, Category.PROMISE, "Brings iron", O, NOW - 90 * DAY), NOW));
    }

    @Test
    void onlyAPerishableClaimCarriesAMarkerBeforeItsAge() {
        assertEquals("", FactProvenance.marker(fact(1, Category.PREFERENCE, "Likes redstone", O, NOW), O, NOW));
        assertEquals("", FactProvenance.marker(fact(2, Category.PROMISE, "Brings iron", O, NOW - DAY), O, NOW));
        assertEquals("", FactProvenance.marker(fact(3, Category.PROFILE, "Builds at night", O, NOW), O, NOW));
        assertEquals(
                "inferred",
                FactProvenance.marker(fact(4, Category.NOTE, "Village to the north", T, NOW - DAY), O, NOW));
        assertEquals(
                "inferred stale",
                FactProvenance.marker(fact(5, Category.PLACE, "Base at 1 2 3", O, NOW - 40 * DAY), O, NOW));
        String checked =
                GoalOutcomeFact.text("Collect 32 oak logs", GoalOutcomeFact.End.VERIFIED, "+32 minecraft:oak_log", 160);
        assertEquals("verified", FactProvenance.marker(fact(6, Category.NOTE, checked, T, NOW - DAY), O, NOW));
        assertEquals(
                "verified stale", FactProvenance.marker(fact(7, Category.PLACE, checked, T, NOW - 31 * DAY), O, NOW));
    }

    @Test
    void aMissingFactHasNoClaimAtAll() {
        assertEquals(FactProvenance.Source.INFERRED, FactProvenance.source(null, O));
        assertFalse(FactProvenance.perishable(null));
        assertFalse(FactProvenance.expired(null, NOW));
        assertEquals("", FactProvenance.marker(null, O, NOW));
    }
}
