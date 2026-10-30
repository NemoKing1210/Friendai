package dev.friendai.features.memory.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TranscriptLimitsTest {
    @Test
    void theFileCapIsGenerousEnoughThatALoweredCountCannotEmptyAnExistingFile() {
        TranscriptLimits wide = TranscriptLimits.fromConfig(200, 400, 7);
        TranscriptLimits narrow = TranscriptLimits.fromConfig(20, 100, 7);
        assertTrue(narrow.maxFileBytes() >= 2L * 1024 * 1024, "narrow cap " + narrow.maxFileBytes());
        assertTrue(wide.maxFileBytes() >= narrow.maxFileBytes());
        assertEquals(200, wide.maxEntries());
        assertEquals(7L * 24 * 60 * 60 * 1000, wide.retentionMillis());
    }
}
