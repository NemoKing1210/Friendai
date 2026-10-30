package dev.friendai.features.memory.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptBookTest {
    private static final TranscriptLimits LIMITS = new TranscriptLimits(10, 40, 10_000, 64 * 1024);
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID GUS = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @Test
    void idsAreMonotonicAndTextIsNormalized() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        TranscriptEntry first = book.add(ALEX, "Alex", Speaker.PLAYER, "  hello \n  there ", 1_000);
        TranscriptEntry second = book.add(ALEX, "Alex", Speaker.COMPANION, "hi", 2_000);
        assertEquals(1, first.id());
        assertEquals(2, second.id());
        assertEquals("hello there", first.text());
        assertEquals("Alex", first.playerName());
        assertNull(book.add(ALEX, "Alex", Speaker.PLAYER, "   ", 3_000));
    }

    @Test
    void countCapEvictsTheOldest() {
        TranscriptBook book = new TranscriptBook(new TranscriptLimits(2, 40, 10_000, 64 * 1024));
        book.add(ALEX, "Alex", Speaker.PLAYER, "one", 1_000);
        book.add(ALEX, "Alex", Speaker.PLAYER, "two", 2_000);
        book.add(ALEX, "Alex", Speaker.PLAYER, "three", 3_000);
        assertEquals(
                List.of(2L, 3L),
                book.snapshot().stream().map(TranscriptEntry::id).toList());
    }

    @Test
    void ageCapEvictsOldLines() {
        TranscriptBook book = new TranscriptBook(new TranscriptLimits(10, 40, 1_000, 64 * 1024));
        book.add(ALEX, "Alex", Speaker.PLAYER, "old", 10_000);
        book.add(ALEX, "Alex", Speaker.PLAYER, "new", 12_000);
        assertEquals(
                List.of("new"),
                book.snapshot().stream().map(TranscriptEntry::text).toList());
    }

    @Test
    void queryFiltersByPlayerWindowAndOrder() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        book.add(ALEX, "Alex", Speaker.PLAYER, "a1", 1_000);
        book.add(ALEX, "Alex", Speaker.COMPANION, "a2", 2_000);
        book.add(GUS, "Gus", Speaker.PLAYER, "g1", 3_000);
        book.add(ALEX, "Alex", Speaker.PLAYER, "a3", 4_000);

        assertEquals(List.of("a1", "a2", "a3"), texts(book.query(ALEX, 0, 0, 10, false)));
        assertEquals(List.of("a3", "a2"), texts(book.query(ALEX, 0, 0, 2, true)));
        assertEquals(List.of("g1"), texts(book.query(GUS, 0, 0, 10, false)));
        assertEquals(List.of("a3"), texts(book.query(ALEX, 3_000, 0, 10, false)));
        assertEquals(List.of("a1"), texts(book.query(ALEX, 0, 2, 10, false)));
    }

    @Test
    void beforeIdPagesBackwardsWithoutRepeating() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        for (int i = 1; i <= 4; i++) {
            book.add(ALEX, "Alex", Speaker.PLAYER, "m" + i, i * 1_000L);
        }
        assertEquals(List.of("m4", "m3"), texts(book.query(ALEX, 0, 0, 2, true)));
        assertEquals(List.of("m2", "m1"), texts(book.query(ALEX, 0, 3, 2, true)));
    }

    @Test
    void clearPlayerKeepsOtherConversations() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        book.add(ALEX, "Alex", Speaker.PLAYER, "a", 1_000);
        book.add(GUS, "Gus", Speaker.PLAYER, "g", 2_000);
        assertEquals(1, book.clearPlayer(ALEX));
        assertEquals(
                List.of("g"),
                book.snapshot().stream().map(TranscriptEntry::text).toList());
        assertEquals(1, book.clearAll());
        assertTrue(book.snapshot().isEmpty());
    }

    @Test
    void oversizedTextIsCutAtTheLimit() {
        TranscriptBook book = new TranscriptBook(new TranscriptLimits(10, 5, 10_000, 64 * 1024));
        TranscriptEntry entry = book.add(ALEX, "Alex", Speaker.PLAYER, "abcdefgh", 1_000);
        assertEquals("abcd…", entry.text());
    }

    @Test
    void roundTripKeepsIdsAndNextId() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        book.add(ALEX, "Alex", Speaker.PLAYER, "hello", 1_000);
        book.add(ALEX, "Alex", Speaker.COMPANION, "hi", 2_000);
        String json = TranscriptFile.encode(book).toJson();

        TranscriptBook loaded = TranscriptFile.decode(json, LIMITS);
        assertEquals(2, loaded.snapshot().size());
        assertEquals(3, loaded.nextId());
        assertEquals("hi", loaded.snapshot().get(1).text());
        assertEquals(Speaker.COMPANION, loaded.snapshot().get(1).speaker());
        assertEquals(3, loaded.add(ALEX, "Alex", Speaker.PLAYER, "again", 3_000).id());
    }

    @Test
    void aFutureFileVersionIsUnsupportedAndAnInvalidOneIsCorrupt() {
        String future = "{\"version\":99,\"nextId\":1,\"entries\":[]}";
        String invalid = "{\"version\":0,\"nextId\":1,\"entries\":[]}";
        assertThrows(
                dev.friendai.foundation.persistence.UnsupportedStoreVersionException.class,
                () -> TranscriptFile.decode(future, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> TranscriptFile.decode(invalid, LIMITS));
    }

    @Test
    void malformedEntriesAreDroppedAndNextIdIsRepaired() {
        String json = "{\"version\":1,\"nextId\":1,\"entries\":["
                + "{\"id\":1,\"atMillis\":1000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"kept\"},"
                + "{\"id\":1,\"atMillis\":1000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"duplicate id\"},"
                + "{\"id\":2,\"atMillis\":1000,\"playerUuid\":\"not-a-uuid\",\"speaker\":\"PLAYER\",\"text\":\"bad\"},"
                + "{\"id\":3,\"atMillis\":1000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"GHOST\",\"text\":\"bad speaker\"},"
                + "{\"id\":4,\"atMillis\":1000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"also kept\"}]}";
        TranscriptBook book = TranscriptFile.decode(json, LIMITS);
        assertEquals(
                List.of("kept", "also kept"),
                book.snapshot().stream().map(TranscriptEntry::text).toList());
        assertEquals(5, book.nextId());
    }

    @Test
    void decodeKeepsTheNewestEntiesUnderTheCountCap() {
        String json = "{\"version\":1,\"nextId\":4,\"entries\":["
                + "{\"id\":1,\"atMillis\":1000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"one\"},"
                + "{\"id\":2,\"atMillis\":2000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"two\"},"
                + "{\"id\":3,\"atMillis\":3000,\"playerUuid\":\"00000000-0000-0000-0000-0000000000a1\","
                + "\"playerName\":\"Alex\",\"speaker\":\"PLAYER\",\"text\":\"three\"}]}";
        TranscriptBook book = TranscriptFile.decode(json, new TranscriptLimits(2, 40, 10_000, 64 * 1024));
        assertEquals(
                List.of("two", "three"),
                book.snapshot().stream().map(TranscriptEntry::text).toList());
    }

    @Test
    void dirtyStartsFalseAndRevisionsGrow() {
        TranscriptBook book = new TranscriptBook(LIMITS);
        assertTrue(!book.isDirty());
        long before = book.revision();
        book.add(ALEX, "Alex", Speaker.PLAYER, "x", 1_000);
        assertTrue(book.isDirty());
        assertTrue(book.revision() > before);
        book.markClean();
        assertTrue(!book.isDirty());
    }

    private static List<String> texts(List<TranscriptEntry> entries) {
        return entries.stream().map(TranscriptEntry::text).toList();
    }
}
