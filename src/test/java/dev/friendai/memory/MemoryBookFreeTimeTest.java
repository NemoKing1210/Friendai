package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemoryBookFreeTimeTest {
    @Test
    void freeTimeRoundTrips() {
        MemoryBook book = new MemoryBook();
        assertTrue(book.freeTime().isEmpty());
        long before = book.revision();
        assertTrue(book.setFreeTime("freetime:v1:{}"));
        assertFalse(book.setFreeTime("freetime:v1:{}"), "an equal value is not a change");
        assertTrue(book.revision() > before);
        MemoryBook back = MemoryBook.fromFile(book.toFile());
        assertEquals("freetime:v1:{}", back.freeTime().orElseThrow());
        assertTrue(back.setFreeTime(null));
        assertTrue(back.freeTime().isEmpty());
    }
}
