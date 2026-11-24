package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VoiceTranscriptTest {
    @Test
    void theLimitIsCountedInUtf8BytesNotCharacters() {
        String ascii = "a".repeat(VoiceTranscript.MAX_WIRE_BYTES);
        assertEquals(VoiceTranscript.Readiness.READY, VoiceTranscript.classify(ascii));

        String cyrillic = "я".repeat(250);
        assertEquals(VoiceTranscript.MAX_WIRE_BYTES, VoiceTranscript.wireBytes(cyrillic));
        assertEquals(VoiceTranscript.Readiness.READY, VoiceTranscript.classify(cyrillic));

        String oneTooMany = "я".repeat(251);
        assertEquals(VoiceTranscript.Readiness.TOO_LONG, VoiceTranscript.classify(oneTooMany));
        assertEquals(
                VoiceTranscript.Readiness.TOO_LONG,
                VoiceTranscript.classify("🙂".repeat(126)),
                "a surrogate pair costs four bytes");
    }

    @Test
    void blankTextIsEmptyAndSurroundingSpaceIsDroppedNotCut() {
        assertEquals(VoiceTranscript.Readiness.EMPTY, VoiceTranscript.classify(null));
        assertEquals(VoiceTranscript.Readiness.EMPTY, VoiceTranscript.classify("   "));
        assertEquals("build a house", VoiceTranscript.normalize("  build a house  "));
        assertEquals(
                VoiceTranscript.Readiness.READY,
                VoiceTranscript.classify(" " + "a".repeat(VoiceTranscript.MAX_WIRE_BYTES) + " "),
                "the trailing space is not part of the command");
    }
}
