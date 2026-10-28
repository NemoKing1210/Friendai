package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpeechGateTest {
    private static final UUID SELF = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void speaksRepliesAddressedToTheLocalPlayer() {
        assertTrue(SpeechGate.shouldSpeak(SELF, SELF, false, false));
    }

    @Test
    void ignoresOtherPlayersUnlessGuestsAreEnabled() {
        assertFalse(SpeechGate.shouldSpeak(OTHER, SELF, false, false));
        assertTrue(SpeechGate.shouldSpeak(OTHER, SELF, true, false));
    }

    @Test
    void staysSilentWhileRecording() {
        assertFalse(SpeechGate.shouldSpeak(SELF, SELF, true, true));
    }

    @Test
    void ignoresMissingAddresses() {
        assertFalse(SpeechGate.shouldSpeak(null, SELF, true, false));
        assertFalse(SpeechGate.shouldSpeak(SELF, null, true, false));
    }
}
