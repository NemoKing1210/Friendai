package dev.friendai.features.speech.stt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.VoiceAuth;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Yandex SpeechKit's synchronous recognition endpoint: raw PCM and a required language tag. */
public final class YandexSttProtocol implements SttProtocol {
    public static final String ID = "yandex_stt";
    public static final int SAMPLE_RATE = 16000;

    @Override
    public PreparedRequest prepare(ResolvedStt settings, byte[] wav16k, byte[] pcm16k) {
        if (settings.language() == null || settings.language().isBlank()) {
            throw new UserFacingException(UserMessage.of("friendai.voice.error.unsupported_language"));
        }
        StringBuilder query = new StringBuilder("?format=lpcm&sampleRateHertz=").append(SAMPLE_RATE);
        if (settings.folderId() != null && !settings.folderId().isBlank()) {
            query.append("&folderId=").append(encode(settings.folderId()));
        }
        query.append("&lang=").append(encode(settings.language()));
        URI uri = OpenAiSttProtocol.join(settings.baseUrl(), "/speech/v1/stt:recognize");
        return new PreparedRequest(
                URI.create(uri + query.toString()),
                VoiceAuth.YANDEX.headers(settings.apiKey()),
                pcm16k,
                "application/octet-stream");
    }

    @Override
    public String parse(JsonObject response) {
        JsonElement result = response == null ? null : response.get("result");
        if (result == null
                || !result.isJsonPrimitive()
                || !result.getAsJsonPrimitive().isString()
                || result.getAsString().isBlank()) {
            throw failed();
        }
        return result.getAsString().strip();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
