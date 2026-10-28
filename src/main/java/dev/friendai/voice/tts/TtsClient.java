package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceHttp;
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
