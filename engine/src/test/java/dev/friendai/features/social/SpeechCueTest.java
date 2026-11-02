package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SpeechCueTest {
    @Test
    void explicitPhrasesMapToTheirCue() {
        assertEquals(SpeechCue.GREETING, SpeechCue.of("Hello there"));
        assertEquals(SpeechCue.THANKS, SpeechCue.of("thank you!"));
        assertEquals(SpeechCue.APOLOGY, SpeechCue.of("Sorry about that"));
        assertEquals(SpeechCue.CALM_DOWN, SpeechCue.of("calm down, it is fine"));
        assertEquals(SpeechCue.CHALLENGE, SpeechCue.of("let's go"));
    }

    @Test
    void aPromiseOrAnOrdinaryRequestIsNotACue() {
        assertEquals(SpeechCue.NONE, SpeechCue.of("I promise to bring the diamonds"));
        assertEquals(SpeechCue.NONE, SpeechCue.of("mine 5 iron ore"));
        assertEquals(SpeechCue.NONE, SpeechCue.of(""));
        assertEquals(SpeechCue.NONE, SpeechCue.of(null));
    }

    @Test
    void aCueAsksForAFaceOrLeavesItAlone() {
        assertEquals(Mood.PLEASED, SpeechCue.THANKS.mood());
        assertEquals(Mood.DISPLEASED, SpeechCue.APOLOGY.mood());
        assertEquals(Mood.FOCUSED, SpeechCue.CHALLENGE.mood());
        assertNull(SpeechCue.NONE.mood());
    }

    @Test
    void everyPhraseTheWordListShipsIsRecognisedInItsOwnLanguage() {
        assertEquals(SpeechCue.GREETING, SpeechCue.of("hey there"));
        assertEquals(SpeechCue.GREETING, SpeechCue.of("hi there"));
        assertEquals(SpeechCue.GREETING, SpeechCue.of("good morning"));
        assertEquals(SpeechCue.GREETING, SpeechCue.of("привет"));
        assertEquals(SpeechCue.GREETING, SpeechCue.of("здравствуй"));
        assertEquals(SpeechCue.GREETING, SpeechCue.of("доброе утро"));
        assertEquals(SpeechCue.THANKS, SpeechCue.of("thanks"));
        assertEquals(SpeechCue.THANKS, SpeechCue.of("спасибо"));
        assertEquals(SpeechCue.THANKS, SpeechCue.of("благодарю"));
        assertEquals(SpeechCue.APOLOGY, SpeechCue.of("прости"));
        assertEquals(SpeechCue.APOLOGY, SpeechCue.of("извини"));
        assertEquals(SpeechCue.CALM_DOWN, SpeechCue.of("успокойся"));
        assertEquals(SpeechCue.CALM_DOWN, SpeechCue.of("не бойся"));
        assertEquals(SpeechCue.CHALLENGE, SpeechCue.of("вперёд"));
        assertEquals(SpeechCue.CHALLENGE, SpeechCue.of("давай"));
    }

    @Test
    void theStrongestExplicitPhraseWinsWhenALineSaysTwoThings() {
        assertEquals(SpeechCue.APOLOGY, SpeechCue.of("sorry, and thank you"));
        assertEquals(SpeechCue.THANKS, SpeechCue.of("thank you, let's go"));
        assertEquals(SpeechCue.APOLOGY, SpeechCue.of("hello, sorry I am late"));
    }

    @Test
    void everyCueNamesOneOfTheClosedExpressions() {
        assertEquals(Mood.PLEASED, SpeechCue.GREETING.mood());
        assertEquals(Mood.CALM, SpeechCue.CALM_DOWN.mood());
        for (SpeechCue cue : SpeechCue.values()) {
            if (cue == SpeechCue.NONE) {
                assertNull(cue.mood(), "an unrecognised line leaves the face alone");
            } else {
                assertNotNull(cue.mood(), cue.name() + " names no expression");
            }
        }
    }
}
