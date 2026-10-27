package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutoProfileTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final MemoryLimits LIMITS = MemoryLimits.DEFAULTS;

    private static MemoryBook book() {
        MemoryBook book = new MemoryBook();
        book.ensurePlayer(OWNER, "Alice");
        return book;
    }

    private static List<Fact> facts(MemoryBook book) {
        return book.facts(Subject.player(OWNER));
    }

    private static void say(MemoryBook book, String message) {
        AutoProfile.record(book, OWNER, "Alice", message, 100, LIMITS);
    }

    private static Fact only(MemoryBook book) {
        List<Fact> facts = facts(book);
        assertEquals(1, facts.size(), "expected exactly one fact, got " + facts);
        return facts.getFirst();
    }

    @Test
    void anEnglishAddressIsCapturedAsAProfileFact() {
        MemoryBook book = book();
        say(book, "Hi! Call me Boss.");
        Fact fact = only(book);
        assertEquals("Prefers to be called Boss.", fact.text());
        assertEquals(Category.PROFILE, fact.category());
        assertEquals(OWNER, fact.authorUuid());
        assertEquals("Alice", fact.authorName());
    }

    @Test
    void aRussianAddressIsCaptured() {
        MemoryBook book = book();
        say(book, "Привет! Зови меня Босс.");
        assertEquals("Prefers to be called Босс.", only(book).text());
    }

    @Test
    void aNegatedAddressCapturesNothing() {
        MemoryBook book = book();
        say(book, "Don't call me Bob.");
        say(book, "Не называй меня Босс.");
        assertTrue(facts(book).isEmpty(), "a negated address was stored: " + facts(book));
    }

    @Test
    void correctingTheAddressReplacesTheOldOne() {
        MemoryBook book = book();
        say(book, "Call me Boss.");
        say(book, "Actually, call me Chief.");
        assertEquals("Prefers to be called Chief.", only(book).text());
    }

    @Test
    void likesAndDislikesArePreferenceFacts() {
        MemoryBook book = book();
        say(book, "I like redstone.");
        say(book, "I hate spiders.");
        assertEquals(2, facts(book).size());
        assertTrue(facts(book).stream().anyMatch(f -> f.text().equals("Likes redstone.")
                && f.category() == Category.PREFERENCE));
        assertTrue(facts(book).stream().anyMatch(f -> f.text().equals("Dislikes spiders.")
                && f.category() == Category.PREFERENCE));
    }

    @Test
    void flippingAPreferenceEditsOneFact() {
        MemoryBook book = book();
        say(book, "I like redstone.");
        say(book, "I don't like redstone.");
        assertEquals("Dislikes redstone.", only(book).text());
    }

    @Test
    void aRussianPreferenceIsCaptured() {
        MemoryBook book = book();
        say(book, "Мне нравится редстоун.");
        assertEquals("Likes редстоун.", only(book).text());
    }

    @Test
    void aPreferenceForOneThingNeverDuplicates() {
        MemoryBook book = book();
        say(book, "I prefer birch.");
        say(book, "I prefer birch.");
        assertEquals("Prefers birch.", only(book).text());
    }

    @Test
    void quotedTextAndCommandsAreNotEvidence() {
        MemoryBook book = book();
        say(book, "\"I like redstone\"");
        say(book, "`call me Boss`");
        say(book, "/friendai memory clear");
        assertTrue(facts(book).isEmpty(), "quoted text or a command was stored: " + facts(book));
    }

    @Test
    void aBarePronounIsNotAProfile() {
        MemoryBook book = book();
        say(book, "I like it.");
        say(book, "I love you.");
        say(book, "Мне нравится это.");
        assertTrue(facts(book).isEmpty(), "a bare pronoun was stored: " + facts(book));
    }

    @Test
    void aFullBookDoesNotThrowAndKeepsItsFacts() {
        MemoryBook book = book();
        for (int i = 0; i < LIMITS.maxFactsPerPlayer(); i++) {
            book.addFact(Subject.player(OWNER), Category.NOTE, "note " + i, OWNER, "Alice", 1, LIMITS);
        }
        assertDoesNotThrow(() -> say(book, "Call me Boss."));
        assertEquals(LIMITS.maxFactsPerPlayer(), facts(book).size());
        assertFalse(facts(book).stream().anyMatch(f -> f.text().startsWith("Prefers to be called")));
    }

    @Test
    void theLanguageMirrorIsWrittenAndUpdatedInOneFact() {
        MemoryBook book = book();
        book.setOwnerLanguage(OWNER, "Russian");
        AutoProfile.syncLanguage(book, OWNER, "Alice", "Russian", 100, LIMITS);
        assertEquals("Writes in Russian.", only(book).text());
        book.setOwnerLanguage(OWNER, "German");
        AutoProfile.syncLanguage(book, OWNER, "Alice", "German", 101, LIMITS);
        assertEquals("Writes in German.", only(book).text());
    }

    @Test
    void forgettingTheLanguageFactForgetsTheLanguage() {
        MemoryBook book = book();
        book.setOwnerLanguage(OWNER, "Russian");
        AutoProfile.syncLanguage(book, OWNER, "Alice", "Russian", 100, LIMITS);
        book.removeFact(only(book).id());
        assertEquals(Optional.empty(), book.ownerLanguage(OWNER));
        assertTrue(facts(book).isEmpty());
    }

    @Test
    void clearingAPlayersFactsForgetsTheLanguage() {
        MemoryBook book = book();
        book.setOwnerLanguage(OWNER, "Russian");
        AutoProfile.syncLanguage(book, OWNER, "Alice", "Russian", 100, LIMITS);
        book.clearFacts(Subject.player(OWNER));
        assertEquals(Optional.empty(), book.ownerLanguage(OWNER));
    }
}
