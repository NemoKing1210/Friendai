package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.memory.Category;
import dev.friendai.memory.Fact;
import dev.friendai.memory.MemoryBook;
import dev.friendai.memory.MemoryLimits;
import dev.friendai.memory.Subject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerProfileObserverTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final MemoryLimits LIMITS = MemoryLimits.DEFAULTS;

    @Test
    void disabledMemoryStillRefreshesOwnerNameAndLearnsLanguageWithoutCapturingFacts() {
        MemoryBook book = new MemoryBook();
        OwnerProfileObserver observer = new OwnerProfileObserver(OWNER, book);

        observer.observe("Alice", "Привет, пожалуйста, принеси мне домой немного еды.", false, LIMITS, 100);

        assertEquals("Alice", book.player(OWNER).orElseThrow().lastName());
        assertEquals(Optional.of("Russian"), book.ownerLanguage(OWNER));
        assertTrue(book.facts(Subject.player(OWNER)).isEmpty());
    }

    @Test
    void enabledMemoryMirrorsLanguageAndCapturesExplicitOwnerProfileStatements() {
        MemoryBook book = new MemoryBook();
        book.ensurePlayer(OWNER, "Old Name");
        OwnerProfileObserver observer = new OwnerProfileObserver(OWNER, book);

        observer.observe("Alice", "Please call me Boss. I like redstone.", true, LIMITS, 100);

        assertEquals(Optional.of("English"), book.ownerLanguage(OWNER));
        assertEquals("Alice", book.player(OWNER).orElseThrow().lastName());
        List<Fact> facts = book.facts(Subject.player(OWNER));
        assertTrue(facts.stream()
                .anyMatch(f -> f.text().equals("Writes in English.") && f.category() == Category.PROFILE));
        assertTrue(facts.stream()
                .anyMatch(f -> f.text().equals("Prefers to be called Boss.") && f.category() == Category.PROFILE));
        assertTrue(facts.stream()
                .anyMatch(f -> f.text().equals("Likes redstone.") && f.category() == Category.PREFERENCE));
        assertTrue(facts.stream()
                .allMatch(f -> f.authorUuid().equals(OWNER) && f.authorName().equals("Alice")));
    }

    @Test
    void trackerStartsFromPersistedOwnerLanguage() {
        MemoryBook book = new MemoryBook();
        book.ensurePlayer(OWNER, "Alice");
        book.setOwnerLanguage(OWNER, "English");
        OwnerProfileObserver observer = new OwnerProfileObserver(OWNER, book);

        observer.observe("Alice", "Привет, пожалуйста, принеси мне домой немного еды.", true, LIMITS, 100);
        observer.observe("Alice", "Привет, пожалуйста, принеси мне домой немного еды.", true, LIMITS, 101);

        assertEquals(Optional.of("English"), book.ownerLanguage(OWNER));
        assertTrue(book.facts(Subject.player(OWNER)).stream()
                .noneMatch(f -> f.text().equals("Writes in Russian.")));
    }
}
