package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryAuditTest {
    private static final UUID O = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID G = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final UUID X = UUID.fromString("00000000-0000-0000-0000-0000000000a4");
    private static final MemoryLimits L = new MemoryLimits(160, 2, 2);
    private static final MemoryLimits BIG = new MemoryLimits(512, 20, 20);
    private static final long NOW = 3_600_000;
    private static final long DAY = 86_400_000L;

    private final MemoryBook book = new MemoryBook();
    private final Caller owner = new Caller(O, "Steve", AccessLevel.OWNER);
    private final Caller trusted = new Caller(T, "Tia", AccessLevel.TRUSTED);
    private final Caller guest = new Caller(G, "Gus", AccessLevel.GUEST);

    private void seed() {
        book.ensurePlayer(O, "Steve");
        book.setTrusted(T, "Tia", true, 1);
        book.ensurePlayer(G, "Gus");
        book.ensurePlayer(X, "Alex");
        book.addFact(Subject.player(O), Category.PREFERENCE, "Likes redstone", O, "Steve", NOW - 120_000, L);
        book.addFact(Subject.player(O), Category.PLACE, "Base at 1 2 3", O, "Steve", NOW - 7_200_000, L);
        book.addFact(Subject.WORLD, Category.NOTE, "Village to the north", T, "Tia", NOW, L);
        book.addFact(Subject.player(G), Category.PROFILE, "Gus likes cats", G, "Gus", NOW, L);
        book.addFact(Subject.player(X), Category.PROFILE, "Alex builds", X, "Alex", NOW, L);
    }

    @Test
    void ownerCountsEveryBookAndMarksFullAtTheShare() {
        seed();
        String text = MemoryAudit.overview(book, O, owner, L, 90, NOW, "", 40, false, 6000, value -> value);
        assertTrue(text.contains("you 2/2 (full)"), text);
        assertTrue(text.contains("world 1/2"), text);
        assertTrue(text.contains("Gus 1/2"), text);
        assertTrue(text.contains("Alex 1/2"), text);
        assertFalse(text.contains("Likes redstone"), text);
    }

    @Test
    void detailListsIdsCategoryAuthorAndAge() {
        seed();
        String text = MemoryAudit.overview(book, O, owner, L, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("[1] PREFERENCE (owner): Likes redstone — owner, 2m"), text);
        assertTrue(text.contains("[2] PLACE (owner): Base at 1 2 3 — owner, inferred, today"), text);
        assertTrue(text.contains("[3] NOTE (world): Village to the north — Tia, inferred, today"), text);
    }

    @Test
    void plainWordAgeClampsTheFutureAndUsesSingularDay() {
        assertEquals("1 day ago", MemoryAudit.staleAge(NOW - DAY, NOW));
        assertEquals("today", MemoryAudit.staleAge(NOW + DAY, NOW));
    }

    @Test
    void placeAndNoteAgesReadAsPlainWords() {
        MemoryBook aged = new MemoryBook();
        aged.ensurePlayer(O, "Steve");
        aged.addFact(Subject.player(O), Category.PLACE, "Base at 1 2 3", O, "Steve", NOW - 3_600_000, BIG);
        aged.addFact(Subject.player(O), Category.PLACE, "Mine at 4 5 6", O, "Steve", NOW - 3 * DAY, BIG);
        aged.addFact(Subject.player(O), Category.PLACE, "Farm at 7 8 9", O, "Steve", NOW - 14 * DAY, BIG);
        aged.addFact(Subject.WORLD, Category.NOTE, "Village to the north", O, "Steve", NOW - 40 * DAY, BIG);
        aged.addFact(Subject.WORLD, Category.NOTE, "Chest by the river", O, "Steve", NOW + DAY, BIG);
        String text = MemoryAudit.overview(aged, O, owner, BIG, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("PLACE (owner): Base at 1 2 3 — owner, inferred, today"), text);
        assertTrue(text.contains("PLACE (owner): Mine at 4 5 6 — owner, inferred, 3 days ago"), text);
        assertTrue(text.contains("PLACE (owner): Farm at 7 8 9 — owner, inferred, 2 weeks ago"), text);
        assertTrue(text.contains("NOTE (world): Village to the north — owner, inferred stale, over a month ago"), text);
        assertTrue(text.contains("NOTE (world): Chest by the river — owner, inferred, today"), text);
    }

    @Test
    void verifiedGoalOutcomesSaySoAndExpiredClaimsReadStale() {
        MemoryBook goals = new MemoryBook();
        goals.ensurePlayer(O, "Steve");
        String checked =
                GoalOutcomeFact.text("Collect 32 oak logs", GoalOutcomeFact.End.VERIFIED, "+32 minecraft:oak_log", 160);
        String oldChecked =
                GoalOutcomeFact.text("Visit the village", GoalOutcomeFact.End.VERIFIED, "+1 minecraft:bread", 160);
        String guessed = GoalOutcomeFact.text("Make a friend", GoalOutcomeFact.End.UNVERIFIED, null, 160);
        goals.addFact(Subject.player(O), Category.NOTE, checked, O, "Steve", NOW - DAY, BIG);
        goals.addFact(Subject.player(O), Category.PLACE, oldChecked, O, "Steve", NOW - 31 * DAY, BIG);
        goals.addFact(Subject.player(O), Category.NOTE, guessed, O, "Steve", NOW - DAY, BIG);
        String text = MemoryAudit.overview(goals, O, owner, BIG, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("— owner, verified, 1 day ago"), text);
        assertTrue(text.contains("— owner, verified stale, over a month ago"), text);
        assertTrue(text.contains("— owner, inferred, 1 day ago"), text);
    }

    @Test
    void otherCategoriesKeepTheCompactAge() {
        MemoryBook aged = new MemoryBook();
        aged.ensurePlayer(O, "Steve");
        aged.addFact(Subject.player(O), Category.PREFERENCE, "Likes redstone", O, "Steve", NOW - 3 * DAY, BIG);
        aged.addFact(Subject.player(O), Category.PROMISE, "Brings iron", O, "Steve", NOW - 40 * DAY, BIG);
        aged.addFact(Subject.player(O), Category.PROFILE, "Builds at night", O, "Steve", NOW - 14 * DAY, BIG);
        String text = MemoryAudit.overview(aged, O, owner, BIG, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("PREFERENCE (owner): Likes redstone — owner, 3d"), text);
        assertTrue(text.contains("PROMISE (owner): Brings iron — owner, 40d"), text);
        assertTrue(text.contains("PROFILE (owner): Builds at night — owner, 14d"), text);
        assertFalse(text.contains("days ago"), text);
        assertFalse(text.contains("today"), text);
    }

    @Test
    void aSubjectNarrowsTheListAndImpliesDetail() {
        seed();
        String text = MemoryAudit.overview(book, O, owner, L, 90, NOW, "owner", 40, null, 6000, value -> value);
        assertTrue(text.contains("Likes redstone"), text);
        assertFalse(text.contains("Village"), text);
        assertFalse(text.contains("Alex builds"), text);
    }

    @Test
    void trustedSeesWorldAndSelfAndNothingElse() {
        seed();
        String text = MemoryAudit.overview(book, O, trusted, L, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("Village"), text);
        assertFalse(text.contains("Likes redstone"), text);
        assertFalse(text.contains("Alex builds"), text);
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryAudit.overview(book, O, trusted, L, 90, NOW, "Alex", 40, true, 6000, value -> value));
    }

    @Test
    void guestSeesOnlyItself() {
        seed();
        String text = MemoryAudit.overview(book, O, guest, L, 90, NOW, "", 40, true, 6000, value -> value);
        assertTrue(text.contains("Gus likes cats"), text);
        assertFalse(text.contains("Village"), text);
        assertFalse(text.contains("Likes redstone"), text);
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryAudit.overview(book, O, guest, L, 90, NOW, "world", 40, true, 6000, value -> value));
    }

    @Test
    void oldFactsAreRedactedOnRead() {
        MemoryBook secretBook = new MemoryBook();
        secretBook.ensurePlayer(O, "Steve");
        secretBook.addFact(Subject.player(O), Category.NOTE, "key=SECRET", O, "Steve", NOW, L);
        String text = MemoryAudit.overview(
                secretBook, O, owner, L, 90, NOW, "", 40, true, 6000, value -> value.replace("SECRET", "***"));
        assertTrue(text.contains("***"), text);
        assertFalse(text.contains("SECRET"), text);
    }

    @Test
    void anUnknownNameIsNotGuessed() {
        seed();
        assertThrows(
                IllegalArgumentException.class,
                () -> MemoryAudit.overview(book, O, owner, L, 90, NOW, "Nobody", 40, true, 6000, value -> value));
    }
}
