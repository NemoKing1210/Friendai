package dev.friendai.client.tts;

import dev.friendai.client.voicechat.VoiceChatPlayback;
import dev.friendai.features.speech.tts.PlaybackBackends;
import dev.friendai.features.speech.tts.PlaybackGate;
import dev.friendai.features.speech.tts.TtsClip;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where synthesized clips go: the built-in audio line, or the optional voice-chat channel when the owner chose it
 * and it can carry the reply. Both play under one gate, so a single stop silences whichever is speaking.
 */
final class SpeechOutput {
    private static final Logger LOGGER = LoggerFactory.getLogger("friendai/tts");
    /** Simple Voice Chat's mod id, as Fabric Loader knows it. */
    private static final String VOICECHAT_MOD = "voicechat";

    private final PlaybackGate gate = new PlaybackGate();
    private final SpeechPlayback builtin = new SpeechPlayback(gate);
    /** Null without Simple Voice Chat: the class behind it names that mod's API and must not load without it. */
    private final SpeechBackend voiceChat =
            FabricLoader.getInstance().isModLoaded(VOICECHAT_MOD) ? VoiceChatPlayback.create(gate) : null;
    /** Set once the voice-chat path has thrown; the built-in path then speaks for the rest of the run. */
    private volatile boolean voiceChatFailed;

    /** An admission permit taken before synthesis; a later {@link #stop()} rejects the clip it was taken for. */
    PlaybackGate.Permit permit() {
        return gate.permit();
    }

    /** Blocks until the clip has played or was stopped; {@code configured} is the owner's backend choice. */
    void play(TtsClip clip, String configured, SpeechBackend.Request request, PlaybackGate.Permit permit) {
        if (!viaVoiceChat(clip, configured, request, permit)) {
            builtin.play(clip, request, permit);
        }
    }

    /** Cuts the current clip off, whichever backend plays it. Safe to call when nothing is playing. */
    void stop() {
        // Cancels the shared gate, which the voice-chat sender asks before every frame.
        builtin.stop();
    }

    void close() {
        stop();
    }

    private boolean viaVoiceChat(
            TtsClip clip, String configured, SpeechBackend.Request request, PlaybackGate.Permit permit) {
        if (voiceChat == null || voiceChatFailed) {
            return false;
        }
        try {
            boolean positional = request.spatial() != null && request.companionId() != null;
            String chosen = PlaybackBackends.choose(configured, voiceChat.ready(), positional);
            return PlaybackBackends.VOICECHAT.equals(chosen) && voiceChat.play(clip, request, permit);
        } catch (RuntimeException | LinkageError e) {
            // An incompatible build of the optional mod must cost the voice-chat path, never the reply.
            voiceChatFailed = true;
            LOGGER.warn("Voice chat playback failed; using the built-in output from now on: {}", e.toString());
            return false;
        }
    }
}
