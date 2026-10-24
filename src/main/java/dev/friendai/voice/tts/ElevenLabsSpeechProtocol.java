package dev.friendai.voice.tts;

import com.google.gson.JsonObject;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** ElevenLabs text-to-speech, asking for raw 24 kHz PCM so no WAV parsing is needed. */
public final class ElevenLabsSpeechProtocol implements TtsProtocol {
    public static final int SAMPLE_RATE = 24000;

    @Override
    public TtsHttpRequest build(TtsRequest request) {
        if (request.voice() == null || request.voice().isBlank()) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice"));
        }
        String base = baseUrl(request.baseUrl());
        String voice = URLEncoder.encode(request.voice().strip(), StandardCharsets.UTF_8).replace("+", "%20");
        JsonObject settings = new JsonObject();
        settings.addProperty("speed", request.speed());
        JsonObject body = new JsonObject();
        body.addProperty("text", request.text());
        body.addProperty("model_id", request.model());
        body.add("voice_settings", settings);
        Map<String, String> headers = new LinkedHashMap<>();
        TtsAuth.XI_API_KEY.apply(headers, request.apiKey());
        return new TtsHttpRequest(
                "POST",
                URI.create(base + "/v1/text-to-speech/" + voice + "?output_format=pcm_24000"),
                headers,
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TtsClip parse(byte[] response) {
        return new TtsClip(response.clone(), SAMPLE_RATE);
    }

    private static String baseUrl(String configured) {
        String base = configured == null || configured.isBlank()
                ? TtsPresets.ELEVENLABS_DEFAULT_BASE
                : configured.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
