package dev.friendai.features.speech.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlaybackBackendsTest {
    @Test
    void aMissingOrUnknownChoiceIsTheBuiltInPath() {
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.normalize(null));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.normalize(""));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.normalize("plasmo"));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.normalize("VoiceChat"));
        assertEquals(PlaybackBackends.VOICECHAT, PlaybackBackends.normalize("voicechat"));
    }

    @Test
    void theBuiltInPathComesFirstBecauseItIsTheDefault() {
        assertEquals(List.of(PlaybackBackends.BUILTIN, PlaybackBackends.VOICECHAT), PlaybackBackends.ids());
    }

    @Test
    void voiceChatCarriesAPositionalReplyOnlyWhileItIsReady() {
        assertEquals(PlaybackBackends.VOICECHAT, PlaybackBackends.choose("voicechat", true, true));
    }

    @Test
    void aSelectedVoiceChatThatIsNotInstalledOrConnectedFallsBackToTheBuiltInPath() {
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("voicechat", false, true));
    }

    @Test
    void aReplyWithoutAPositionStaysOnTheBuiltInPath() {
        // The settings preview and a reply with "voice comes from the companion" off have no entity to attach to.
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("voicechat", true, false));
    }

    @Test
    void anInstalledVoiceChatIsNeverUsedUnasked() {
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("builtin", true, true));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose(null, true, true));
        assertEquals(PlaybackBackends.BUILTIN, PlaybackBackends.choose("plasmo", true, true));
    }
}
