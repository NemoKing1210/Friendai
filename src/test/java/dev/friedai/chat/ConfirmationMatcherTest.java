package dev.friedai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friedai.chat.ConfirmationMatcher.Answer;
import org.junit.jupiter.api.Test;

class ConfirmationMatcherTest {
    private static final String NAME = "Friendai";

    private static Answer match(String text) {
        return ConfirmationMatcher.match(text, NAME);
    }

    @Test
    void englishYes() {
        for (String text : new String[] {"yes", "Yeah", "OK", "confirm", "do it"}) {
            assertEquals(Answer.YES, match(text), text);
        }
    }

    @Test
    void russianYes() {
        for (String text : new String[] {"да", "Ага", "конечно", "подтверждаю", "давай"}) {
            assertEquals(Answer.YES, match(text), text);
        }
    }

    @Test
    void englishNo() {
        for (String text : new String[] {"no", "Nope", "cancel", "don't", "do not"}) {
            assertEquals(Answer.NO, match(text), text);
        }
    }

    @Test
    void russianNo() {
        for (String text : new String[] {"нет", "отмена", "не надо"}) {
            assertEquals(Answer.NO, match(text), text);
        }
    }

    @Test
    void leadingNameAndPunctuation() {
        assertEquals(Answer.YES, match("Friendai, да."));
        assertEquals(Answer.NO, match("friendai: нет!"));
    }

    @Test
    void sentencesAreNone() {
        assertEquals(Answer.NONE, match("да, и принеси дерево"));
        assertEquals(Answer.NONE, match("yes please bring wood"));
    }

    @Test
    void emptyIsNone() {
        assertEquals(Answer.NONE, match(""));
        assertEquals(Answer.NONE, match("   "));
        assertEquals(Answer.NONE, match(null));
    }
}
