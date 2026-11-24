package dev.friendai.features.memory;

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
    void anAddressIsNotAClause() {
        MemoryBook book = book();
        say(book, "Call me when you arrive.");
        say(book, "Зови меня когда придёшь.");
        assertTrue(facts(book).isEmpty(), "a clause was stored as a name: " + facts(book));
    }

    @Test
    void anAddressKeepsNoTrailingPunctuation() {
        MemoryBook book = book();
        say(book, "Call me Chief, but only when I ask.");
        assertEquals("Prefers to be called Chief.", only(book).text());
        MemoryBook russian = book();
        say(russian, "Зови меня Босс, пожалуйста.");
        assertEquals("Prefers to be called Босс.", only(russian).text());
    }

    @Test
    void preferringToBeCalledSetsTheSameAddressSlot() {
        MemoryBook book = book();
        say(book, "Call me Boss.");
        say(book, "I prefer to be called Chief.");
        assertEquals("Prefers to be called Chief.", only(book).text());
        assertEquals(Category.PROFILE, only(book).category());
    }

    @Test
    void clearingFactsForgetsTheLanguageEvenWithoutAMirrorFact() {
        MemoryBook book = book();
        book.setOwnerLanguage(OWNER, "Russian");
        book.clearFacts(Subject.player(OWNER));
        assertEquals(Optional.empty(), book.ownerLanguage(OWNER));
        book.setOwnerLanguage(OWNER, "Russian");
        book.clearAllFacts();
        assertEquals(Optional.empty(), book.ownerLanguage(OWNER));
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
        assertTrue(facts(book).stream()
                .anyMatch(f -> f.text().equals("Likes redstone.") && f.category() == Category.PREFERENCE));
        assertTrue(facts(book).stream()
                .anyMatch(f -> f.text().equals("Dislikes spiders.") && f.category() == Category.PREFERENCE));
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
    void theFinalStatementOfASlotWinsInEitherOrder() {
        MemoryBook first = book();
        say(first, "Call me Boss. Actually, call me Chief.");
        assertEquals("Prefers to be called Chief.", only(first).text());

        MemoryBook second = book();
        say(second, "Actually, call me Chief. Call me Boss.");
        assertEquals("Prefers to be called Boss.", only(second).text());
    }

    @Test
    void aCorrectionWithADifferentPhrasingStillFollowsTheText() {
        MemoryBook book = book();
        say(book, "My name is Chief. Call me Boss.");
        assertEquals("Prefers to be called Boss.", only(book).text());

        MemoryBook reversed = book();
        say(reversed, "Call me Boss. My name is Chief.");
        assertEquals("Prefers to be called Chief.", only(reversed).text());
    }

    @Test
    void oppositePreferencesInOneMessageResolveToTheLastStatementInEnglishAndRussian() {
        MemoryBook english = book();
        say(english, "I like redstone. Actually I don't like redstone.");
        assertEquals("Dislikes redstone.", only(english).text());

        MemoryBook reversed = book();
        say(reversed, "I don't like redstone. Actually I like redstone.");
        assertEquals("Likes redstone.", only(reversed).text());

        MemoryBook russian = book();
        say(russian, "Мне нравится редстоун. На самом деле мне не нравится редстоун.");
        assertEquals("Dislikes редстоун.", only(russian).text());

        MemoryBook russianReversed = book();
        say(russianReversed, "Мне не нравится редстоун. На самом деле мне нравится редстоун.");
        assertEquals("Likes редстоун.", only(russianReversed).text());
    }

    @Test
    void unrelatedClausesInOneMessageAreBothStored() {
        MemoryBook book = book();
        say(book, "I don't like spiders. Call me Boss.");
        assertEquals(2, facts(book).size(), "both statements are facts: " + facts(book));
        assertTrue(facts(book).stream().anyMatch(f -> f.text().equals("Dislikes spiders.")));
        assertTrue(facts(book).stream().anyMatch(f -> f.text().equals("Prefers to be called Boss.")));

        MemoryBook two = book();
        say(two, "Call me Boss. I like redstone.");
        assertEquals(2, facts(two).size(), "both statements are facts: " + two.facts(Subject.player(OWNER)));
    }

    @Test
    void aNegatedEarlierSentenceDoesNotNegateALaterNamingRequest() {
        MemoryBook book = book();
        say(book, "I don't. Call me Boss.");
        assertEquals("Prefers to be called Boss.", only(book).text());
    }

    @Test
    void aQuotedStatementIsIgnoredWhileTheUnquotedOneAfterItIsStored() {
        MemoryBook book = book();
        say(book, "\"Call me Nobody\" — call me Boss.");
        assertEquals("Prefers to be called Boss.", only(book).text());
    }

    @Test
    void anActuallyNegatedNamingRequestDoesNotOverrideTheRealOne() {
        MemoryBook book = book();
        say(book, "Call me Boss, but don't call me Chief.");
        assertEquals("Prefers to be called Boss.", only(book).text());

        MemoryBook russian = book();
        say(russian, "Зови меня Босс. Не называй меня Шеф.");
        assertEquals("Prefers to be called Босс.", only(russian).text());

        MemoryBook onlyNegated = book();
        say(onlyNegated, "Don't call me Chief.");
        assertTrue(facts(onlyNegated).isEmpty(), "a negated request is not an address: " + facts(onlyNegated));
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
