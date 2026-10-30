package dev.friendai.features.memory.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.memory.MenuStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptMenuActionsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    void clearEmptiesTheBookAndReportsTheCount() {
        TranscriptBook book = new TranscriptBook(TranscriptLimits.DEFAULTS);
        book.add(OWNER, "Steve", Speaker.PLAYER, "one", 1_000);
        book.add(OWNER, "Steve", Speaker.COMPANION, "two", 2_000);
        MenuStatus status = TranscriptMenuActions.clear(book);
        assertTrue(status.ok());
        assertEquals("friendai.menu.transcript.status.cleared", status.message().key());
        assertEquals(java.util.List.of(2), status.message().args());
        assertTrue(book.snapshot().isEmpty());
    }

    @Test
    void clearingAnEmptyBookStillSucceeds() {
        MenuStatus status = TranscriptMenuActions.clear(new TranscriptBook(TranscriptLimits.DEFAULTS));
        assertTrue(status.ok());
        assertFalse(status.message().args().isEmpty());
    }
}
