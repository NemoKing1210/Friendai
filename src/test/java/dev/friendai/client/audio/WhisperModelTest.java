package dev.friendai.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhisperModelTest {
    @Test
    void anUnknownModelIsNotSilentlyBase() {
        assertTrue(WhisperModel.find("parakeet-v3").isEmpty());
        assertTrue(WhisperModel.find("").isEmpty());
        assertEquals(WhisperModel.BASE, WhisperModel.byId("parakeet-v3"));
        assertEquals(WhisperModel.SMALL, WhisperModel.find("SMALL").orElseThrow());
    }
}
