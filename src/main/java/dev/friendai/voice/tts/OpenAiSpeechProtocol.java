package dev.friendai.voice.tts;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** The OpenAI-compatible {@code POST /audio/speech} endpoint, which answers with a WAV file. */
public final class OpenAiSpeechProtocol implements TtsProtocol {
    @Override
    public TtsHttpRequest build(TtsRequest request) {
        String base = baseUrl(request.baseUrl());
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("input", request.text());
        body.addProperty("voice", request.voice());
        body.addProperty("response_format", "wav");
        body.addProperty("speed", request.speed());
        Map<String, String> headers = new LinkedHashMap<>();
        TtsAuth.BEARER.apply(headers, request.apiKey());
        return new TtsHttpRequest(
                "POST", URI.create(base + "/audio/speech"), headers, body.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TtsClip parse(byte[] response) {
        return WavCodec.decode(response);
    }

    private static String baseUrl(String configured) {
        String base = configured == null || configured.isBlank() ? TtsPresets.OPENAI_DEFAULT_BASE : configured.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }
}
