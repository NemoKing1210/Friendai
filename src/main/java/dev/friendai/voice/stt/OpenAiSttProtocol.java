package dev.friendai.voice.stt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.MultipartBody;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedStt;
import dev.friendai.voice.VoiceAuth;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/** OpenAI's transcription endpoint, also used by any OpenAI-compatible provider such as Groq. */
public final class OpenAiSttProtocol implements SttProtocol {
    public static final String ID = "openai_transcriptions";

    @Override
    public PreparedRequest prepare(ResolvedStt settings, byte[] wav16k, byte[] pcm16k) {
        MultipartBody body = new MultipartBody(UUID.randomUUID().toString().replace("-", ""))
                .file("file", "audio.wav", "audio/wav", wav16k)
                .field("model", settings.model() == null ? "" : settings.model());
        if (settings.language() != null && !settings.language().isBlank()) {
            body.field("language", settings.language());
        }
        Map<String, String> headers = VoiceAuth.BEARER.headers(settings.apiKey());
        return new PreparedRequest(join(settings.baseUrl(), "/audio/transcriptions"), headers,
                body.build(), body.contentType());
    }

    @Override
    public String parse(JsonObject response) {
        JsonElement text = response == null ? null : response.get("text");
        if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()
                || text.getAsString().isBlank()) {
            throw failed();
        }
        return text.getAsString().strip();
    }

    static URI join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
