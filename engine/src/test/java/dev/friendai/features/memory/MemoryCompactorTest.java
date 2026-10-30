package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryCompactorTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID GUS = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final MemoryLimits L = new MemoryLimits(160, 20, 30);
    private static final long NOW = 100_000;

    private record Spec(Subject subject, Category category, String text, UUID author) {}

    /** A file-loaded book can hold the exact duplicates the live writer collapses, which is what compaction sees. */
    private static MemoryBook loaded(Spec... specs) {
        MemoryFile file = new MemoryFile();
        file.nextId = specs.length + 1;
        for (int i = 0; i < specs.length; i++) {
            Spec spec = specs[i];
            MemoryFile.FactEntry entry = new MemoryFile.FactEntry();
            entry.id = i + 1;
            entry.subject = spec.subject().key();
            entry.category = spec.category().name();
            entry.text = spec.text();
            entry.authorUuid = spec.author().toString();
            entry.authorName = spec.author().equals(OWNER) ? "owner" : "Gus";
            entry.createdAt = NOW;
            file.facts.add(entry);
        }
        return MemoryBook.fromFile(file);
    }

    private static Spec note(Subject subject, String text, UUID author) {
        return new Spec(subject, Category.NOTE, text, author);
    }

    private static Spec pref(Subject subject, String text, UUID author) {
        return new Spec(subject, Category.PREFERENCE, text, author);
    }

    @Test
    void exactDuplicatesMergeToTheNewestText() {
        MemoryBook book = loaded(
                pref(Subject.player(OWNER), "Likes redstone", OWNER),
                pref(Subject.player(OWNER), "likes  REDSTONE", OWNER));
        MemoryCompactor.Plan plan = MemoryCompactor.plan(book, OWNER, null, L, true, 5);
        assertEquals(1, plan.merges().size(), plan.toString());
        assertEquals(2, plan.before());
        assertEquals(1, plan.after());
        MemoryCompactor.Merge merge = plan.merges().get(0);
        assertEquals(2, merge.ids().size());
        assertTrue(merge.text().toLowerCase().contains("redstone"));
    }

    @Test
    void promisesAreNeverMerged() {
        MemoryBook book = loaded(
                new Spec(Subject.player(OWNER), Category.PROMISE, "I will bring wood", OWNER),
                new Spec(Subject.player(OWNER), Category.PROMISE, "I will bring wood", OWNER));
        assertTrue(MemoryCompactor.plan(book, OWNER, null, L, true, 5).empty());
    }

    @Test
    void ownerAuthoredFactsAreNotMergedWithoutTheOwnersRequest() {
        MemoryBook book = loaded(
                note(Subject.player(OWNER), "the base is north", OWNER),
                note(Subject.player(OWNER), "the base is north", OWNER));
        assertTrue(MemoryCompactor.plan(book, OWNER, null, L, false, 5).empty());
        assertFalse(MemoryCompactor.plan(book, OWNER, null, L, true, 5).empty());
    }

    @Test
    void aSupersededNoteKeepsTheLongerText() {
        MemoryBook book = loaded(
                note(Subject.player(GUS), "base by the river", GUS),
                note(Subject.player(GUS), "the base by the river is ready", GUS));
        MemoryCompactor.Plan plan = MemoryCompactor.plan(book, OWNER, null, L, true, 5);
        assertEquals(1, plan.merges().size());
        assertEquals("the base by the river is ready", plan.merges().get(0).text());
        assertTrue(plan.merges().get(0).reason().contains("superseded"));
    }

    @Test
    void aNearDuplicateNoteMergesAndADistinctOneDoesNot() {
        MemoryBook book = loaded(
                note(Subject.player(GUS), "farm near the river is ready", GUS),
                note(Subject.player(GUS), "farm near the river is nearly ready", GUS),
                note(Subject.player(GUS), "the mine has iron and gold", GUS));
        MemoryCompactor.Plan plan = MemoryCompactor.plan(book, OWNER, null, L, true, 5);
        assertEquals(1, plan.merges().size());
        assertEquals(2, plan.merges().get(0).ids().size());
        assertFalse(plan.merges().get(0).ids().contains(3));
    }

    @Test
    void differentCategoriesNeverMerge() {
        MemoryBook book =
                loaded(pref(Subject.player(GUS), "likes cats", GUS), note(Subject.player(GUS), "likes cats", GUS));
        assertTrue(MemoryCompactor.plan(book, OWNER, null, L, true, 5).empty());
    }

    @Test
    void thePlanIsDeterministicAndCapped() {
        java.util.List<Spec> specs = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            specs.add(pref(Subject.player(OWNER), "same " + i, OWNER));
            specs.add(pref(Subject.player(OWNER), "same " + i, OWNER));
        }
        MemoryBook book = loaded(specs.toArray(Spec[]::new));
        MemoryCompactor.Plan first = MemoryCompactor.plan(book, OWNER, null, L, true, 2);
        MemoryCompactor.Plan second = MemoryCompactor.plan(book, OWNER, null, L, true, 2);
        assertEquals(first.merges(), second.merges());
        assertEquals(2, first.merges().size());
    }

    @Test
    void applyFreesAUnitandLetsANewFactIn() {
        MemoryLimits two = new MemoryLimits(160, 2, 30);
        MemoryBook full = loaded(
                pref(Subject.player(OWNER), "Likes redstone", OWNER),
                pref(Subject.player(OWNER), "Likes redstone", OWNER));
        MemoryCompactor.Plan plan = MemoryCompactor.plan(full, OWNER, null, two, true, 5);
        MemoryCompactor.Applied applied = MemoryCompactor.apply(full, plan, OWNER, "owner", NOW, two);
        assertEquals(1, applied.added());
        assertEquals(1, applied.removed());
        assertEquals(1, applied.after());
        assertTrue(full.addFact(Subject.player(OWNER), Category.PLACE, "Base at 1 2 3", OWNER, "owner", NOW, two)
                        .id()
                > 0);
    }

    @Test
    void aSubjectFilterLeavesOtherBooksAlone() {
        MemoryBook book = loaded(
                pref(Subject.player(OWNER), "Likes redstone", OWNER),
                pref(Subject.player(OWNER), "Likes redstone", OWNER),
                pref(Subject.player(GUS), "Likes cats", GUS),
                pref(Subject.player(GUS), "Likes cats", GUS));
        MemoryCompactor.Plan plan = MemoryCompactor.plan(book, OWNER, Subject.player(OWNER), L, true, 5);
        assertEquals(1, plan.merges().size());
        assertTrue(plan.merges().get(0).ids().stream().allMatch(id -> id <= 2));
    }
}
