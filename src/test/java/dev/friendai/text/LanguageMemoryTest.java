package dev.friendai.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LanguageMemoryTest {
    private static Optional<String> sample(String language) {
        return Optional.of(language);
    }

    @Test
    void theFirstConfidentSampleIsRemembered() {
        LanguageMemory memory = LanguageMemory.startingAt(null);
        assertEquals(Optional.of("Russian"), memory.observe(sample("Russian")));
        assertEquals(Optional.of("Russian"), memory.language());
    }

    @Test
    void oneMessageInAnotherLanguageDoesNotSwitch() {
        LanguageMemory memory = LanguageMemory.startingAt("Russian");
        assertTrue(memory.observe(sample("English")).isEmpty());
        assertEquals(Optional.of("Russian"), memory.language());
    }

    @Test
    void aSustainedChangeSwitches() {
        LanguageMemory memory = LanguageMemory.startingAt("Russian");
        memory.observe(sample("English"));
        memory.observe(sample("English"));
        assertEquals(Optional.of("Russian"), memory.language());
        assertEquals(Optional.of("English"), memory.observe(sample("English")));
        assertEquals(Optional.of("English"), memory.language());
    }

    @Test
    void aMessageInTheCurrentLanguageResetsTheStreak() {
        LanguageMemory memory = LanguageMemory.startingAt("Russian");
        memory.observe(sample("English"));
        memory.observe(sample("English"));
        memory.observe(sample("Russian"));
        memory.observe(sample("English"));
        memory.observe(sample("English"));
        assertEquals(Optional.of("Russian"), memory.language());
        assertEquals(Optional.of("English"), memory.observe(sample("English")));
    }

    @Test
    void anUnnamedMessageChangesNothing() {
        LanguageMemory memory = LanguageMemory.startingAt("Russian");
        for (int i = 0; i < 5; i++) {
            assertTrue(memory.observe(Optional.empty()).isEmpty());
        }
        assertEquals(Optional.of("Russian"), memory.language());
    }

    @Test
    void theLearnedLanguageMayBeUnknown() {
        LanguageMemory memory = LanguageMemory.startingAt("  ");
        assertTrue(memory.language().isEmpty());
        assertEquals(Optional.of("German"), memory.observe(sample("German")));
    }
}
