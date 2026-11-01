package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.social.Happening;
import dev.friendai.features.social.HappeningJournal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryBookHappeningTest {
    private static Happening happening(String key, String text, int tick) {
        return new Happening(Happening.Kind.DEATH, Happening.Importance.NORMAL, tick, key, text);
    }

    @Test
    void aHappeningIsRecordedForItsOwnerOnly() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();
        UUID guest = UUID.randomUUID();

        book.recordHappening(owner, happening("death:1", "you died to a creeper", 100));

        assertEquals(1, book.journal(owner).size());
        assertEquals(
                "you died to a creeper",
                book.journal(owner).recent(1).getFirst().text());
        assertEquals(0, book.journal(guest).size(), "another player's journal stays empty");
    }

    @Test
    void aRepeatedKeyReplacesTheEntryInsteadOfGrowingTheJournal() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();

        book.recordHappening(owner, happening("death:1", "first", 100));
        book.recordHappening(owner, happening("death:1", "second", 200));

        assertEquals(1, book.journal(owner).size());
        assertEquals("second", book.journal(owner).recent(1).getFirst().text());
    }

    @Test
    void theJournalIsBoundedAndNewestFirst() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();

        for (int i = 0; i < HappeningJournal.MAX_ENTRIES + 4; i++) {
            book.recordHappening(owner, happening("death:" + i, "event " + i, i));
        }

        assertEquals(HappeningJournal.MAX_ENTRIES, book.journal(owner).size());
        assertEquals(
                "event " + (HappeningJournal.MAX_ENTRIES + 3),
                book.journal(owner).recent(1).getFirst().text());
    }

    @Test
    void aJournalSurvivesTheRecordRoundTrip() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();
        book.recordHappening(owner, happening("respawn:1", "you respawned", 50));
        book.recordHappening(owner, happening("death:2", "you died to lava", 60));

        MemoryBook reloaded = MemoryBook.fromRecord(MemoryRecord.capture(book));

        List<Happening> entries = reloaded.journal(owner).recent(2);
        assertEquals(2, entries.size());
        assertEquals("you died to lava", entries.getFirst().text());
        assertEquals("you respawned", entries.getLast().text());
    }

    @Test
    void anOlderFormatReadsWithNoHappeningsAndIsNotRefused() {
        MemoryRecord old =
                new MemoryRecord(2, 5, 1, List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), null);
        MemoryBook book = MemoryBook.fromRecord(old);
        assertTrue(book.journal(UUID.randomUUID()).size() == 0, "an older file has no journal");
    }
}
