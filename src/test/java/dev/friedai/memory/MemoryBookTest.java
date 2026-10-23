package dev.friedai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryBookTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

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
}
