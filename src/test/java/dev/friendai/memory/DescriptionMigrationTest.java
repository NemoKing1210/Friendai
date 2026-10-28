package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DescriptionMigrationTest {
    @Test
    void malformedDescriptionDoesNotEraseUnrelatedFacts() {
        String json = "{\"version\":4,\"description\":{\"summary\":\"" + "x".repeat(301) + "\",\"details\":\"\"},"
                + "\"ownerProfile\":{\"language\":\"not a tag\",\"address\":\"\"},"
                + "\"facts\":[{\"id\":1,\"subject\":\"world\",\"category\":\"NOTE\",\"text\":\"Known shelter\"}]}";
        MemoryFile file = assertDoesNotThrow(() -> new com.google.gson.Gson().fromJson(json, MemoryFile.class));
        MemoryBook book = MemoryBook.fromFile(file);
        assertEquals(1, book.allFacts().size());
        assertEquals("Known shelter", book.allFacts().getFirst().text());
        assertEquals(CompanionDescription.EMPTY, book.description());
        assertEquals(OwnerProfile.EMPTY, book.ownerProfile());
    }

    @Test
    void legacySummaryMigratesWithoutLosingWork() {
        MemoryFile old = new MemoryFile();
        old.version = 3;
        old.persona = "Calm guide";
        old.goals = "goals:v1:retained";
        old.freeTime = "freetime:v1:retained";
        MemoryBook book = MemoryBook.fromFile(old);
        assertEquals("Calm guide", book.description().summary());
        assertEquals("", book.description().details());
        assertEquals("goals:v1:retained", book.goals().orElseThrow());
        assertEquals("freetime:v1:retained", book.freeTime().orElseThrow());
        assertEquals(4, book.toFile().version);
        assertNull(book.toFile().persona);
    }

    @Test
    void descriptionAndOwnerProfileRoundTrip() {
        MemoryBook book = new MemoryBook();
        book.setDescription(new CompanionDescription("Guide", "Build carefully.\nExplain briefly."));
        book.setOwnerProfile(new OwnerProfile("ru-RU", "Alex"));
        MemoryBook copy = MemoryBook.fromFile(book.toFile());
        assertEquals(book.description(), copy.description());
        assertEquals(book.ownerProfile(), copy.ownerProfile());
        book.setDescription(CompanionDescription.EMPTY);
        assertTrue(book.persona().isEmpty());
    }

    @Test
    void validatesTextAndLanguageBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionDescription("x".repeat(301), ""));
        assertThrows(IllegalArgumentException.class, () -> new CompanionDescription("", "x".repeat(4001)));
        assertThrows(IllegalArgumentException.class, () -> new CompanionDescription("x\u00a7a", ""));
        assertThrows(IllegalArgumentException.class, () -> new OwnerProfile("not a tag", ""));
        assertThrows(IllegalArgumentException.class, () -> new OwnerProfile("auto", "x".repeat(81)));
        assertEquals("ru-RU", new OwnerProfile("ru-ru", "").language());
    }
}
