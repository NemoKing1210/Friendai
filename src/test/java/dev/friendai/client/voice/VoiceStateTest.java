package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoiceStateTest {
    @AfterEach
    void reset() {
        VoiceState.set(VoiceState.Kind.IDLE, null);
    }

    @Test
    void partialTextIsCappedAndReplaced() {
        VoiceState.set(VoiceState.Kind.RECORDING, null);
        VoiceState.setPartial("a".repeat(1_000));
        assertEquals(
                VoiceState.MAX_PARTIAL_CHARS + 1, VoiceState.current().partial().length());

        VoiceState.setPartial(" a new partial ");
        assertEquals("a new partial", VoiceState.current().partial());
    }

    @Test
    void aStalePartialAfterRecordingEndsIsIgnored() {
        VoiceState.set(VoiceState.Kind.RECORDING, null);
        VoiceState.setPartial("heard");
        assertEquals("heard", VoiceState.current().partial());

        VoiceState.set(VoiceState.Kind.TRANSCRIBING, null);
        VoiceState.setPartial("too late");
        assertNull(VoiceState.current().partial(), "partials belong to recording only");
        assertEquals(VoiceState.Kind.TRANSCRIBING, VoiceState.current().kind());
    }

    @Test
    void aBlankPartialKeepsTheRecordingLabel() {
        VoiceState.set(VoiceState.Kind.RECORDING, null);
        VoiceState.setPartial("   ");
        assertNull(VoiceState.current().partial());
    }
}
