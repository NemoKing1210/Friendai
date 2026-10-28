package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompanionNameTest {
    @Test
    void trimsAndCollapsesWhitespace() {
        assertEquals(Optional.of("Bob The Builder"), CompanionName.parse("  Bob \n The\tBuilder  "));
    }

    @Test
    void keepsUnicodeNames() {
        assertEquals(Optional.of("Боб"), CompanionName.parse("Боб"));
        assertEquals(Optional.of("Стив 🙂"), CompanionName.parse("Стив 🙂"));
    }

    @Test
    void rejectsEmptyNames() {
        assertTrue(CompanionName.parse(null).isEmpty());
        assertTrue(CompanionName.parse("   ").isEmpty());
    }

    @Test
    void rejectsNamesOverTheLimit() {
        assertEquals(
                CompanionName.MAX_LENGTH,
                CompanionName.parse("x".repeat(CompanionName.MAX_LENGTH))
                        .orElseThrow()
                        .length());
        assertTrue(CompanionName.parse("x".repeat(CompanionName.MAX_LENGTH + 1)).isEmpty());
    }

    @Test
    void rejectsFormattingCodesAndControlCharacters() {
        assertTrue(CompanionName.parse("§cBob").isEmpty());
        assertTrue(CompanionName.parse("Bob\u0000").isEmpty());
    }
}
