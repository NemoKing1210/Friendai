package dev.friendai.voice.stt;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedStt;
import dev.friendai.voice.VoiceHttp;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Sends a recording to the provider named by the protocol and returns its text. */
public final class SttClient {
    private final VoiceHttp http;
    private final Map<String, SttProtocol> protocols;

    private SttClient(VoiceHttp http, Map<String, SttProtocol> protocols) {
        this.http = http;
        this.protocols = protocols;
    }

    /** The protocol ids must match {@link SttPresets} exactly, or every cloud call would fail. */
    public static SttClient withBuiltins(VoiceHttp http) {
        return new SttClient(http, Map.of(
                OpenAiSttProtocol.ID, new OpenAiSttProtocol(),
                YandexSttProtocol.ID, new YandexSttProtocol()));
    }

    public CompletableFuture<String> transcribe(ResolvedStt settings, byte[] wav16k, byte[] pcm16k) {
        SttProtocol protocol = protocols.get(settings.protocol());
        if (protocol == null) {
            return CompletableFuture.failedFuture(
                    new UserFacingException(UserMessage.of("friendai.voice.error.failed")));
        }
        PreparedRequest request;
        try {
            request = protocol.prepare(settings, wav16k, pcm16k);
        } catch (RuntimeException e) {
            // A malformed base URL throws here; the player sees one recognition failure.
            return CompletableFuture.failedFuture(e instanceof UserFacingException userFacing
                    ? userFacing
                    : new UserFacingException(UserMessage.of("friendai.voice.error.failed")));
        }
        return http.postJson(request).thenApply(protocol::parse);
    }
}
