package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
