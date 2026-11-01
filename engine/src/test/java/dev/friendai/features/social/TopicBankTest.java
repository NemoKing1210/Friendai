package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TopicBankTest {
    private static final List<String> CODES = List.of("en", "ru", "de", "fr", "es", "pt", "uk", "zh");

    private static int questionMarks(String opener) {
        int marks = 0;
        for (int i = 0; i < opener.length(); i++) {
            char c = opener.charAt(i);
            if (c == '?' || c == '？') {
                marks++;
            }
        }
        return marks;
    }

    @Test
    void everyLanguageHasItsOwnBoundedBank() {
        Set<List<String>> banks = new HashSet<>();
        for (String code : CODES) {
            List<String> openers = TopicBank.forLanguage(code);
            assertNotNull(openers, code);
            assertFalse(openers.isEmpty(), code + " has no openers");
            assertTrue(openers.size() <= TopicBank.MAX_OPENERS, code + " exceeds the opener cap");
            assertEquals(openers.size(), new HashSet<>(openers).size(), code + " repeats an opener");
            for (String opener : openers) {
                assertFalse(opener.isBlank(), code + " has a blank opener");
                assertTrue(
                        opener.length() <= TopicBank.MAX_LENGTH,
                        code + " has an opener of " + opener.length() + " characters");
            }
            banks.add(openers);
        }
        assertEquals(CODES.size(), banks.size(), "two languages share one bank");
    }

    @Test
    void anOpenerAsksAtMostOneQuestion() {
        for (String code : CODES) {
            for (String opener : TopicBank.forLanguage(code)) {
                int marks = questionMarks(opener);
                assertTrue(marks <= 1, code + " opener asks more than one question: " + opener);
                assertEquals(marks == 1, TopicBank.asks(opener), code + " disagrees about asking: " + opener);
            }
        }
    }

    @Test
    void anUnknownCodeFallsBackToEnglish() {
        List<String> english = TopicBank.forLanguage("en");
        assertSame(english, TopicBank.forLanguage("xx"));
        assertSame(english, TopicBank.forLanguage(""));
        assertSame(english, TopicBank.forLanguage("   "));
        assertSame(english, TopicBank.forLanguage(null));
        assertSame(english, TopicBank.forLanguage("klingon"));
    }

    @Test
    void aLearnedLanguageNameResolvesToItsCode() {
        assertEquals("ru", TopicBank.codeOf("Russian"));
        assertEquals("uk", TopicBank.codeOf(" ukrainian "));
        assertEquals("zh", TopicBank.codeOf("Chinese"));
        assertEquals("es", TopicBank.codeOf("es"));
        assertEquals("xx", TopicBank.codeOf("xx"));
        assertEquals(TopicBank.DEFAULT_CODE, TopicBank.codeOf(null));
        assertSame(TopicBank.forLanguage("ru"), TopicBank.forLanguage("Russian"));
    }

    @Test
    void askingIsRecognisedInBothQuestionMarks() {
        assertTrue(TopicBank.asks("What did you get up to while I was away?"));
        assertTrue(TopicBank.asks("我不在的时候，你在忙什么？"));
        assertFalse(TopicBank.asks("The light is good today."));
        assertFalse(TopicBank.asks(null));
    }
}
