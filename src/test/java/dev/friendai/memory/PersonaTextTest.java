package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PersonaTextTest {
    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals(Optional.of("Calm and kind"), PersonaText.parse("  Calm \n and\tkind  "));
    }

    @Test
    void acceptsNonLatinText() {
        assertEquals(Optional.of("Спокойный и добрый"), PersonaText.parse("Спокойный и добрый"));
    }

    @Test
    void refusesNullBlankAndTooLong() {
        assertTrue(PersonaText.parse(null).isEmpty());
        assertTrue(PersonaText.parse("   ").isEmpty());
        assertTrue(PersonaText.parse("x".repeat(PersonaText.MAX_LENGTH + 1)).isEmpty());
        assertEquals(PersonaText.MAX_LENGTH, PersonaText.parse("x".repeat(PersonaText.MAX_LENGTH)).orElseThrow().length());
    }

    @Test
    void refusesFormattingAndControlCharacters() {
        assertTrue(PersonaText.parse("§cRed").isEmpty());
        assertTrue(PersonaText.parse("a\u0000b").isEmpty());
    }
}
