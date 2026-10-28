package dev.friendai.infrastructure.speech;

import dev.friendai.features.speech.PcmAudio;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.tts.ElevenLabsTtsProtocol;
import dev.friendai.features.speech.tts.OpenAiTtsProtocol;
import dev.friendai.features.speech.tts.TtsProtocol;
import dev.friendai.features.speech.tts.YandexTtsProtocol;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Turns text into audio through the provider named by the protocol. */
public final class TtsClient {
    private final VoiceHttp http;
    private final Map<String, TtsProtocol> protocols;

    private TtsClient(VoiceHttp http, Map<String, TtsProtocol> protocols) {
        this.http = http;
        this.protocols = protocols;
    }

    public static TtsClient withBuiltins(VoiceHttp http) {
        return new TtsClient(
                http,
                Map.of(
                        OpenAiTtsProtocol.ID, new OpenAiTtsProtocol(),
                        ElevenLabsTtsProtocol.ID, new ElevenLabsTtsProtocol(),
                        YandexTtsProtocol.ID, new YandexTtsProtocol()));
    }

    public CompletableFuture<PcmAudio> synthesize(ResolvedTts settings, String text) {
        TtsProtocol protocol = protocols.get(settings.protocol());
        if (protocol == null) {
            return CompletableFuture.failedFuture(
                    new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed")));
        }
        PreparedRequest request;
        try {
            request = protocol.prepare(settings, text);
        } catch (RuntimeException e) {
            // A malformed base URL or voice id throws here; the player sees one synthesis failure.
            return CompletableFuture.failedFuture(
                    e instanceof UserFacingException userFacing
                            ? userFacing
                            : new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed")));
        }
        return http.postBytes(request).thenApply(body -> protocol.parse(body, settings));
    }
}
