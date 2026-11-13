package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaybackBackendsTest {
    @Test
    void aMissingOrUnknownChoiceIsAuto() {
        assertEquals(PlaybackBackends.AUTO, PlaybackBackends.normalize(null));
        assertEquals(PlaybackBackends.AUTO, PlaybackBackends.normalize(""));
        assertEquals(PlaybackBackends.AUTO, PlaybackBackends.normalize("plasmo"));
        assertEquals(PlaybackBackends.AUTO, PlaybackBackends.normalize("Builtin"));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.normalize("builtin"));
    }

    @Test
    void theEarlierVoiceChatChoiceReadsAsAuto() {
        assertEquals(PlaybackBackends.AUTO, PlaybackBackends.normalize("voicechat"));
        assertTrue(PlaybackBackends.known("voicechat"));
        assertFalse(PlaybackBackends.known("plasmo"));
    }

    @Test
    void autoComesFirstBecauseItIsTheDefault() {
        assertEquals(List.of(PlaybackBackends.AUTO, PlaybackBackends.BUILTIN), PlaybackBackends.ids());
    }

    @Test
    void anInstalledAndConnectedVoiceChatCarriesAPositionalReplyByDefault() {
        assertEquals(PlaybackBackends.VOICECHAT, PlaybackBackends.choose(null, true, true));
        assertEquals(PlaybackBackends.VOICECHAT, PlaybackBackends.choose("auto", true, true));
        assertEquals(PlaybackBackends.VOICECHAT, PlaybackBackends.choose("voicechat", true, true));
    }

    @Test
    void withoutVoiceChatTheBuiltInPathSpeaks() {
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("auto", false, true));
    }

    @Test
    void aReplyWithoutAPositionStaysOnTheBuiltInPath() {
        // The settings preview and a reply with "voice comes from the companion" off have no entity to attach to.
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("auto", true, false));
    }

    @Test
    void builtInNeverUsesVoiceChatForOutputOrTheMicrophone() {
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("builtin", true, true));
        assertFalse(PlaybackBackends.voiceChatAllowed("builtin"));
        assertTrue(PlaybackBackends.voiceChatAllowed("auto"));
        assertTrue(PlaybackBackends.voiceChatAllowed(null));
    }
}
