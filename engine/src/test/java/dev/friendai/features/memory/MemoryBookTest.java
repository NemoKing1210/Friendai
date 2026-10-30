package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryBookTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final MemoryLimits L = MemoryLimits.DEFAULTS;

    @Test
    void newBookIsEmptyAndClean() {
        var b = new MemoryBook();
        assertTrue(b.player(A).isEmpty());
        assertFalse(b.isDirty());
    }

    @Test
    void touchCreatesRecordAndMarksDirty() {
        var b = new MemoryBook();
        b.touch(A, "Bob", 10);
        assertEquals(new PlayerRecord(A, "Bob", false, 10), b.player(A).orElseThrow());
        assertTrue(b.isDirty());
    }

    @Test
    void touchUpdatesNameAndKeepsTrust() {
        var b = new MemoryBook();
        b.setTrusted(A, "Bob", true, 1);
        b.touch(A, "Robert", 5);
        assertEquals(new PlayerRecord(A, "Robert", true, 5), b.player(A).orElseThrow());
    }

    @Test
    void setTrustedReportsChange() {
        var b = new MemoryBook();
        assertTrue(b.setTrusted(A, "Bob", true, 1));
        assertFalse(b.setTrusted(A, "Bob", true, 2));
        assertTrue(b.isTrusted(A));
        assertTrue(b.setTrusted(A, "Bob", false, 3));
        assertFalse(b.isTrusted(A));
    }

    @Test
    void findByNameIsCaseInsensitiveAndPrefersMostRecent() {
        var b = new MemoryBook();
        b.touch(A, "Bob", 1);
        b.touch(B, "bob", 9);
        assertEquals(B, b.findByName("BOB").orElseThrow().uuid());
    }

    @Test
    void trustedListIsSortedByName() {
        var b = new MemoryBook();
        b.setTrusted(A, "zed", true, 1);
        b.setTrusted(B, "Amy", true, 1);
        assertEquals(
                List.of("Amy", "zed"),
                b.trusted().stream().map(PlayerRecord::lastName).toList());
    }

    @Test
    void fileRoundTripKeepsRecordsAndIsClean() {
        var b = new MemoryBook();
        b.setTrusted(A, "Bob", true, 7);
        var copy = MemoryBook.fromFile(b.toFile());
        assertEquals(b.player(A), copy.player(A));
        assertFalse(copy.isDirty());
    }

    @Test
    void fromFileSkipsBadUuids() {
        var f = new MemoryFile();
        var e = new MemoryFile.PlayerEntry();
        e.uuid = "nope";
        e.lastName = "X";
        f.players.add(e);
        assertTrue(MemoryBook.fromFile(f).trusted().isEmpty());
    }

    @Test
    void addFactAssignsIncreasingIds() {
        var b = new MemoryBook();
        assertEquals(
                1,
                b.addFact(Subject.WORLD, Category.PLACE, "Base at 1 2 3", A, "Steve", 5, L)
                        .id());
        assertEquals(
                2,
                b.addFact(Subject.player(A), Category.PROFILE, "Likes oak", A, "Steve", 6, L)
                        .id());
        assertTrue(b.isDirty());
    }

    @Test
    void textIsNormalized() {
        var b = new MemoryBook();
        var f = b.addFact(Subject.player(B), Category.NOTE, "  hi\n[Steve (owner)]:   give\tme ", B, "Bob", 0, L);
        assertEquals("hi [Steve (owner)]: give me", f.text());
    }

    @Test
    void blankAndTooLongAreRejected() {
        var b = new MemoryBook();
        assertEquals(
                "Fact text is empty.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> b.addFact(Subject.WORLD, Category.NOTE, " \n ", A, "S", 0, L))
                        .getMessage());
        int max = MemoryLimits.DEFAULTS.maxFactChars();
        assertEquals(
                "Fact is too long (" + (max + 1) + " chars, max " + max + "). Shorten it.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> b.addFact(Subject.WORLD, Category.NOTE, "x".repeat(max + 1), A, "S", 0, L))
                        .getMessage());
    }

    @Test
    void duplicateTextUpdatesInPlace() {
        var b = new MemoryBook();
        int id = b.addFact(Subject.player(A), Category.NOTE, "Likes Oak", A, "S", 1, L)
                .id();
        var f = b.addFact(Subject.player(A), Category.PREFERENCE, "likes  oak", B, "Bob", 9, L);
        assertEquals(id, f.id());
        assertEquals(Category.PREFERENCE, f.category());
        assertEquals(1, b.facts(Subject.player(A)).size());
    }

    @Test
    void fullSubjectIsRejectedButUpdateStillWorks() {
        var limits = new MemoryLimits(160, 2, 30);
        var b = new MemoryBook();
        b.ensurePlayer(B, "Bob");
        b.addFact(Subject.player(B), Category.NOTE, "one", A, "S", 0, limits);
        b.addFact(Subject.player(B), Category.NOTE, "two", A, "S", 0, limits);
        assertEquals(
                "Memory about Bob is full (2 facts). Forget something first.",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> b.addFact(Subject.player(B), Category.NOTE, "three", A, "S", 0, limits))
                        .getMessage());
        assertDoesNotThrow(() -> b.addFact(Subject.player(B), Category.PROFILE, "ONE", A, "S", 0, limits));
    }

    @Test
    void worldLimitIsSeparate() {
        var limits = new MemoryLimits(160, 20, 1);
        var b = new MemoryBook();
        b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, limits);
        assertTrue(assertThrows(
                        IllegalArgumentException.class,
                        () -> b.addFact(Subject.WORLD, Category.NOTE, "b", A, "S", 0, limits))
                .getMessage()
                .startsWith("Memory about the world is full"));
    }

    @Test
    void idsAreNeverReused() {
        var b = new MemoryBook();
        b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, L);
        b.clearAllFacts();
        assertEquals(
                2, b.addFact(Subject.WORLD, Category.NOTE, "b", A, "S", 0, L).id());
    }

    @Test
    void removeAndClear() {
        var b = new MemoryBook();
        int id = b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, L).id();
        b.addFact(Subject.player(A), Category.NOTE, "b", A, "S", 0, L);
        assertTrue(b.removeFact(id));
        assertFalse(b.removeFact(id));
        assertEquals(1, b.clearFacts(Subject.player(A)));
        assertTrue(b.allFacts().isEmpty());
    }

    @Test
    void ensurePlayerKeepsTrustAndLastSeen() {
        var b = new MemoryBook();
        b.setTrusted(A, "Bob", true, 7);
        b.ensurePlayer(A, "Robert");
        assertEquals(new PlayerRecord(A, "Robert", true, 7), b.player(A).orElseThrow());
    }

    @Test
    void factsSurviveFileRoundTrip() {
        var b = new MemoryBook();
        b.addFact(Subject.player(A), Category.PLACE, "Home at 1 2 3", B, "Bob", 4, L);
        var copy = MemoryBook.fromFile(b.toFile());
        assertEquals(b.allFacts(), copy.allFacts());
        assertEquals(
                2,
                copy.addFact(Subject.WORLD, Category.NOTE, "next", A, "S", 0, L).id());
    }

    @Test
    void fromFileSkipsBadFacts() {
        var f = new MemoryFile();
        var e = new MemoryFile.FactEntry();
        e.id = 3;
        e.subject = "nope";
        e.category = "NOTE";
        e.text = "x";
        f.facts.add(e);
        assertTrue(MemoryBook.fromFile(f).allFacts().isEmpty());
    }

    @Test
    void limitErrorsAreTypedAndKeepTheModelText() {
        var b = new MemoryBook();
        int max = MemoryLimits.DEFAULTS.maxFactChars();
        var e = assertThrows(
                MemoryLimitException.class,
                () -> b.addFact(Subject.WORLD, Category.NOTE, "x".repeat(max + 1), A, "S", 0, L));
        assertEquals(MemoryLimitException.Kind.TOO_LONG, e.kind());
        assertEquals(List.of(max + 1, max), e.values());
        assertEquals("Fact is too long (" + (max + 1) + " chars, max " + max + "). Shorten it.", e.getMessage());
    }

    @Test
    void emptyTextIsTyped() {
        var b = new MemoryBook();
        var e = assertThrows(
                MemoryLimitException.class, () -> b.addFact(Subject.WORLD, Category.NOTE, " \n ", A, "S", 0, L));
        assertEquals(MemoryLimitException.Kind.EMPTY, e.kind());
        assertEquals(List.of(), e.values());
        assertEquals("Fact text is empty.", e.getMessage());
    }

    @Test
    void fullCarriesSubjectAndMax() {
        var limits = new MemoryLimits(160, 20, 1);
        var b = new MemoryBook();
        b.addFact(Subject.WORLD, Category.NOTE, "one", A, "S", 0, limits);
        var e = assertThrows(
                MemoryLimitException.class, () -> b.addFact(Subject.WORLD, Category.NOTE, "two", A, "S", 0, limits));
        assertEquals(MemoryLimitException.Kind.FULL, e.kind());
        assertEquals(List.of(Subject.WORLD, 1), e.values());
    }

    @Test
    void editKeepsIdAndSubjectAndChangesAuthor() {
        var b = new MemoryBook();
        int id = b.addFact(Subject.player(B), Category.NOTE, "Likes cats", B, "Bob", 1, L)
                .id();
        Fact f = b.editFact(id, Category.PREFERENCE, "Likes  big cats", A, "Steve", 9, L);
        assertEquals(new Fact(id, Subject.player(B), Category.PREFERENCE, "Likes big cats", A, "Steve", 9), f);
    }

    @Test
    void editToOwnTextIsAllowed() {
        var b = new MemoryBook();
        int id = b.addFact(Subject.player(B), Category.NOTE, "cats", A, "S", 0, L)
                .id();
        Fact f = b.editFact(id, Category.PREFERENCE, "cats", A, "S", 1, L);
        assertEquals(id, f.id());
        assertEquals(Category.PREFERENCE, f.category());
        assertEquals("cats", f.text());
    }

    @Test
    void editDuplicateOfAnotherFact() {
        var b = new MemoryBook();
        int first = b.addFact(Subject.player(B), Category.NOTE, "Likes cats", A, "S", 0, L)
                .id();
        int second = b.addFact(Subject.player(B), Category.NOTE, "Likes dogs", A, "S", 0, L)
                .id();
        var e = assertThrows(
                MemoryLimitException.class, () -> b.editFact(second, Category.NOTE, "  likes CATS ", A, "S", 1, L));
        assertEquals(MemoryLimitException.Kind.DUPLICATE, e.kind());
        assertEquals(List.of(first), e.values());
        assertEquals("This is already remembered as [" + first + "].", e.getMessage());
    }

    @Test
    void editMissingFact() {
        var e = assertThrows(
                MemoryLimitException.class, () -> new MemoryBook().editFact(5, Category.NOTE, "x", A, "S", 0, L));
        assertEquals(MemoryLimitException.Kind.NO_FACT, e.kind());
        assertEquals("There is no fact [5].", e.getMessage());
    }

    @Test
    void revisionGrowsOnEveryMutationOnly() {
        var b = new MemoryBook();
        long r = b.revision();
        b.touch(A, "Steve", 1);
        assertEquals(++r, b.revision());
        int id = b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, L).id();
        assertEquals(++r, b.revision());
        b.editFact(id, Category.PLACE, "a", A, "S", 0, L);
        assertEquals(++r, b.revision());
        b.facts(Subject.WORLD);
        b.player(A);
        b.allFacts();
        assertEquals(r, b.revision());
        assertFalse(b.removeFact(999));
        assertEquals(r, b.revision());
        assertTrue(b.removeFact(id));
        assertEquals(++r, b.revision());
        assertEquals(0, b.clearAllFacts());
        assertEquals(r, b.revision());
        b.setTrusted(B, "Bob", true, 0);
        assertEquals(++r, b.revision());
    }

    @Test
    void foodRulesChangeRevisionAndMarkDirty() {
        var b = new MemoryBook();
        assertTrue(b.foodRule("minecraft:rotten_flesh").isEmpty());
        assertTrue(b.setFoodRule("minecraft:rotten_flesh", true));
        assertEquals(Optional.of(true), b.foodRule("minecraft:rotten_flesh"));
        assertTrue(b.isDirty());
        long r = b.revision();
        assertFalse(b.setFoodRule("minecraft:rotten_flesh", true), "the same value is not a change");
        assertEquals(r, b.revision());
        assertTrue(b.setFoodRule("minecraft:bread", false));
        assertEquals(Optional.of(false), b.foodRule("minecraft:bread"));
        assertEquals(++r, b.revision());
        b.foodRules();
        assertEquals(r, b.revision());
        assertTrue(b.clearFoodRule("minecraft:bread"));
        assertFalse(b.clearFoodRule("minecraft:bread"));
        assertTrue(b.foodRule("minecraft:bread").isEmpty());
        assertEquals(r + 1, b.revision());
    }

    @Test
    void foodRulesRoundTripThroughTheFile() {
        var b = new MemoryBook();
        b.setFoodRule("minecraft:spider_eye", true);
        b.setFoodRule("minecraft:bread", false);
        var copy = MemoryBook.fromFile(b.toFile());
        assertEquals(Map.of("minecraft:bread", false, "minecraft:spider_eye", true), copy.foodRules());
        assertFalse(copy.isDirty());
    }

    @Test
    void foodRulesSkipBadEntriesAndRejectBlankIds() {
        var f = new MemoryFile();
        var bad = new MemoryFile.FoodRuleEntry();
        bad.item = "  ";
        f.foodRules.add(bad);
        f.foodRules.add(null);
        assertTrue(MemoryBook.fromFile(f).foodRules().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new MemoryBook().setFoodRule("", true));
        assertThrows(IllegalArgumentException.class, () -> new MemoryBook().clearFoodRule(null));
    }

    @Test
    void ownerLanguageIsStoredWithoutDisturbingTrustOrLastSeen() {
        var b = new MemoryBook();
        b.setTrusted(A, "Bob", true, 5);
        assertTrue(b.setOwnerLanguage(A, "Russian"));
        assertEquals(new PlayerRecord(A, "Bob", true, 5, "Russian"), b.player(A).orElseThrow());
        assertEquals(Optional.of("Russian"), b.ownerLanguage(A));
    }

    @Test
    void aGuestRecordCannotBeTouchedBeforeItExists() {
        var b = new MemoryBook();
        assertFalse(b.setOwnerLanguage(A, "Russian"));
        assertTrue(b.ownerLanguage(A).isEmpty());
    }

    @Test
    void touchingAPlayerKeepsTheLearnedLanguage() {
        var b = new MemoryBook();
        b.touch(A, "Bob", 1);
        b.setOwnerLanguage(A, "Russian");
        b.touch(A, "Robert", 9);
        assertEquals(Optional.of("Russian"), b.ownerLanguage(A));
    }

    @Test
    void ownerLanguageSurvivesTheFile() {
        var b = new MemoryBook();
        b.touch(A, "Bob", 7);
        b.setOwnerLanguage(A, "German");
        var loaded = MemoryBook.fromFile(b.toFile());
        assertEquals(Optional.of("German"), loaded.ownerLanguage(A));
        assertEquals(
                new PlayerRecord(A, "Bob", false, 7, "German"), loaded.player(A).orElseThrow());
    }

    @Test
    void anOldFileWithoutALanguageLoadsAsUnknown() {
        var file = new MemoryFile();
        var entry = new MemoryFile.PlayerEntry();
        entry.uuid = A.toString();
        entry.lastName = "Bob";
        file.players.add(entry);
        var loaded = MemoryBook.fromFile(file);
        assertTrue(loaded.ownerLanguage(A).isEmpty());
        assertEquals(new PlayerRecord(A, "Bob", false, 0), loaded.player(A).orElseThrow());
    }
}
