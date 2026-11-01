package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HappeningJournalTest {
    private static Happening happening(String key, String text, int tick) {
        return new Happening(Happening.Kind.TASK_FINISHED, Happening.Importance.NORMAL, tick, key, text);
    }

    @Test
    void theJournalKeepsTheNewestAndDropsTheOldest() {
        HappeningJournal journal = new HappeningJournal();
        for (int i = 0; i < HappeningJournal.MAX_ENTRIES + 3; i++) {
            journal.add(happening("k" + i, "text " + i, i));
        }
        assertEquals(HappeningJournal.MAX_ENTRIES, journal.size());
        assertFalse(journal.contains("k0"));
        assertTrue(journal.contains("k14"));
        assertEquals("k14", journal.recent(1).get(0).key());
    }

    @Test
    void anUpdatedKeyMovesToTheFrontWithoutDuplicating() {
        HappeningJournal journal = new HappeningJournal();
        journal.add(happening("death", "first", 1));
        journal.add(happening("other", "other", 2));
        journal.add(happening("death", "second", 3));
        assertEquals(2, journal.size());
        assertEquals("death", journal.recent(1).get(0).key());
        assertEquals("second", journal.recent(1).get(0).text());
    }

    @Test
    void theTextIsBounded() {
        HappeningJournal journal = new HappeningJournal();
        journal.add(happening("long", "x".repeat(HappeningJournal.MAX_TEXT + 30), 1));
        assertEquals(HappeningJournal.MAX_TEXT, journal.recent(1).get(0).text().length());
    }
}
