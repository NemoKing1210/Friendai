package dev.friendai.features.social;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationCadenceTest {
    @Test
    void aSceneGapAndTheOwnersQuietWindowSilenceTheCompanion() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.ownerSpoke(100);
        assertFalse(cadence.maySpeak(ConversationCadence.Scene.FREE, 150));
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 1_000));
        cadence.recordLine(ConversationCadence.Scene.FREE, 1_000, false, false);
        assertFalse(cadence.maySpeak(ConversationCadence.Scene.FREE, 2_000));
        assertTrue(cadence.maySpeak(ConversationCadence.Scene.FREE, 4_000));
    }

    @Test
    void anUnansweredQuestionBlocksAnotherAndBacksOff() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.REST, 0, true, false);
        assertTrue(cadence.awaitingAnswer());
        assertFalse(cadence.mayAskQuestion());
        assertTrue(cadence.backoff() > 0);
        cadence.ownerSpoke(10);
        assertTrue(cadence.mayAskQuestion());
    }

    @Test
    void anAnsweredLineClearsTheBackoff() {
        ConversationCadence cadence = new ConversationCadence();
        cadence.recordLine(ConversationCadence.Scene.FREE, 0, true, true);
        assertFalse(cadence.awaitingAnswer());
        assertTrue(cadence.backoff() == 0);
    }
}
