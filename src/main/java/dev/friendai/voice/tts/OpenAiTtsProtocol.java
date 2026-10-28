package dev.friendai.voice.tts;

import com.google.gson.JsonObject;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceAuth;
import dev.friendai.voice.WavCodec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** OpenAI's speech endpoint, asked for a WAV clip so the sample rate comes from the file itself. */
public final class OpenAiTtsProtocol implements TtsProtocol {
    public static final String ID = "openai_speech";

    @Override
    public PreparedRequest prepare(ResolvedTts settings, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("input", text);
        body.addProperty("voice", settings.voice());
        body.addProperty("response_format", "wav");
        body.addProperty("speed", settings.speed());
        Map<String, String> headers = new HashMap<>(VoiceAuth.BEARER.headers(settings.apiKey()));
        return new PreparedRequest(
                join(settings.baseUrl(), "/audio/speech"),
                headers,
                body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    @Override
    public PcmAudio parse(byte[] body, ResolvedTts settings) {
        try {
            TtsClip clip = WavCodec.decodeClip(body);
            return new PcmAudio(clip.pcm16le(), clip.sampleRate());
        } catch (IllegalArgumentException e) {
            // A JSON or HTML error body under HTTP 200 must not reach the speakers as noise.
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
    }

    static URI join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }
}
