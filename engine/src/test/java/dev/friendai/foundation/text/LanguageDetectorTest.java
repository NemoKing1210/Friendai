package dev.friendai.foundation.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class LanguageDetectorTest {
    private static Optional<String> detect(String text) {
        return LanguageDetector.detect(text);
    }

    @Test
    void cyrillicWithoutUkrainianLettersIsRussian() {
        assertEquals(Optional.of("Russian"), detect("Привет, как дела? Расскажи мне, что тут можно построить."));
    }

    @Test
    void ukrainianIsNamedByItsOwnLetters() {
        assertEquals(Optional.of("Ukrainian"), detect("Привіт, як справи? Що ти можеш зробити для мене сьогодні?"));
    }

    @Test
    void englishIsNamedByItsStopwords() {
        assertEquals(Optional.of("English"), detect("Could you please bring me the iron pickaxe from the chest?"));
    }

    @Test
    void germanIsNotTakenForEnglish() {
        assertEquals(Optional.of("German"), detect("Kannst du mir bitte die Eisenhacke aus der Kiste holen?"));
    }

    @Test
    void frenchIsNotTakenForSpanish() {
        assertEquals(Optional.of("French"), detect("Peux-tu me donner la pioche en fer qui est dans le coffre ?"));
    }

    @Test
    void spanishIsNotTakenForFrench() {
        assertEquals(Optional.of("Spanish"), detect("¿Puedes traerme el pico de hierro que esta en el cofre?"));
    }

    @Test
    void aShortReplyIsNotEvidence() {
        assertTrue(detect("да").isEmpty());
        assertTrue(detect("ok").isEmpty());
        assertTrue(detect("ok thanks").isEmpty());
    }

    @Test
    void aMixedMessageIsNotEvidence() {
        assertTrue(detect("Привет hello как дела friend").isEmpty());
    }

    @Test
    void aQuotedCommandIsNotEvidence() {
        assertTrue(detect("\"mine 5 logs please now\"").isEmpty());
        assertTrue(detect("`/friendai follow me`").isEmpty());
    }

    @Test
    void aChatCommandDoesNotHideTheSentenceAroundIt() {
        assertEquals(Optional.of("Russian"), detect("/friendai привет, расскажи мне про лес и реку"));
    }

    @Test
    void aLatinSentenceWithoutStopwordsIsNotGuessed() {
        assertTrue(detect("Minecraft bedrock castle build").isEmpty());
    }
}
