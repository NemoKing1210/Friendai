package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
        assertEquals(List.of("Amy", "zed"), b.trusted().stream().map(PlayerRecord::lastName).toList());
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
        assertEquals(1, b.addFact(Subject.WORLD, Category.PLACE, "Base at 1 2 3", A, "Steve", 5, L).id());
        assertEquals(2, b.addFact(Subject.player(A), Category.PROFILE, "Likes oak", A, "Steve", 6, L).id());
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
                assertThrows(IllegalArgumentException.class,
                        () -> b.addFact(Subject.WORLD, Category.NOTE, " \n ", A, "S", 0, L))
                        .getMessage());
        assertEquals(
                "Fact is too long (161 chars, max 160). Shorten it.",
                assertThrows(IllegalArgumentException.class,
                        () -> b.addFact(Subject.WORLD, Category.NOTE, "x".repeat(161), A, "S", 0, L))
                        .getMessage());
    }

    @Test
    void duplicateTextUpdatesInPlace() {
        var b = new MemoryBook();
        int id = b.addFact(Subject.player(A), Category.NOTE, "Likes Oak", A, "S", 1, L).id();
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
                assertThrows(IllegalArgumentException.class,
                        () -> b.addFact(Subject.player(B), Category.NOTE, "three", A, "S", 0, limits))
                        .getMessage());
        assertDoesNotThrow(() -> b.addFact(Subject.player(B), Category.PROFILE, "ONE", A, "S", 0, limits));
    }

    @Test
    void worldLimitIsSeparate() {
        var limits = new MemoryLimits(160, 20, 1);
        var b = new MemoryBook();
        b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, limits);
        assertTrue(
                assertThrows(IllegalArgumentException.class,
                        () -> b.addFact(Subject.WORLD, Category.NOTE, "b", A, "S", 0, limits))
                        .getMessage()
                        .startsWith("Memory about the world is full"));
    }

    @Test
    void idsAreNeverReused() {
        var b = new MemoryBook();
        b.addFact(Subject.WORLD, Category.NOTE, "a", A, "S", 0, L);
        b.clearAllFacts();
        assertEquals(2, b.addFact(Subject.WORLD, Category.NOTE, "b", A, "S", 0, L).id());
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
        assertEquals(2, copy.addFact(Subject.WORLD, Category.NOTE, "next", A, "S", 0, L).id());
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
}
