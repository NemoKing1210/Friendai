package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.features.social.Traits;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryBookTraitsTest {
    @Test
    void traitsDefaultToNeutralForAnUnknownOwner() {
        MemoryBook book = new MemoryBook();
        assertEquals(Traits.NEUTRAL, book.traits(UUID.randomUUID()));
    }

    @Test
    void storingTheSameTraitsDoesNotDirtyTheBook() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();
        book.setTraits(owner, Traits.NEUTRAL);
        long revision = book.revision();
        book.setTraits(owner, Traits.NEUTRAL);
        assertEquals(revision, book.revision(), "an unchanged manner must not look like an edit");
    }

    @Test
    void traitsSurviveTheRecordRoundTrip() {
        MemoryBook book = new MemoryBook();
        UUID owner = UUID.randomUUID();
        book.setTraits(owner, new Traits(1, 2, 0, 0, 1));

        MemoryBook reloaded = MemoryBook.fromRecord(MemoryRecord.capture(book));

        assertEquals(new Traits(1, 2, 0, 0, 1), reloaded.traits(owner));
    }

    @Test
    void anOlderFormatReadsWithNeutralTraits() {
        MemoryRecord old = new MemoryRecord(
                3, 5, 1, List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
        MemoryBook book = MemoryBook.fromRecord(old);
        assertTrue(book.traits(UUID.randomUUID()).neutral(), "an older file starts neutral");
    }
}
