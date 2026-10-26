package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryBookGoalsTest {
    @Test
    void goalsRoundTripThroughTheFile() {
        MemoryBook book = new MemoryBook();
        assertTrue(book.goals().isEmpty());
        assertTrue(book.setGoals("goals:v1:{\"goals\":[]}"));
        assertFalse(book.setGoals("goals:v1:{\"goals\":[]}"), "an equal value is not a change");
        MemoryBook back = MemoryBook.fromFile(book.toFile());
        assertEquals("goals:v1:{\"goals\":[]}", back.goals().orElseThrow());
    }

    @Test
    void oldFilesWithoutGoalsLoadWithNone() {
        MemoryFile file = new MemoryFile();
        assertTrue(MemoryBook.fromFile(file).goals().isEmpty());
    }

    @Test
    void settingGoalsBumpsTheRevisionAndMarksTheBookDirty() {
        MemoryBook book = new MemoryBook();
        long before = book.revision();
        book.setGoals("goals:v1:{}");
        assertTrue(book.revision() > before);
        assertTrue(book.isDirty());
        assertTrue(book.setGoals(null));
        assertTrue(book.goals().isEmpty());
    }
}
