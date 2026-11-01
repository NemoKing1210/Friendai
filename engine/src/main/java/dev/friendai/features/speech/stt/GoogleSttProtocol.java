package dev.friendai.features.speech.stt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.VoiceAuth;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Google Cloud Speech-to-Text: a JSON body with base64 LINEAR16 audio and an API key header. */
public final class GoogleSttProtocol implements SttProtocol {
    public static final String ID = "google_stt";
    public static final int SAMPLE_RATE = 16000;

    @Override
    public PreparedRequest prepare(ResolvedStt settings, byte[] wav16k, byte[] pcm16k) {
        String language = settings.language() == null || settings.language().isBlank() ? "en-US" : settings.language();
        JsonObject config = new JsonObject();
        config.addProperty("encoding", "LINEAR16");
        config.addProperty("sampleRateHertz", SAMPLE_RATE);
        config.addProperty("languageCode", language);
        config.addProperty("enableAutomaticPunctuation", true);
        JsonObject audio = new JsonObject();
        audio.addProperty("content", Base64.getEncoder().encodeToString(pcm16k));
        JsonObject body = new JsonObject();
        body.add("config", config);
        body.add("audio", audio);
        return new PreparedRequest(
                OpenAiSttProtocol.join(settings.baseUrl(), "/v1/speech:recognize"),
                VoiceAuth.GOOGLE.headers(settings.apiKey()),
                body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    @Override
    public String parse(JsonObject response) {
        try {
            JsonArray results = response.getAsJsonArray("results");
            JsonObject alternative = results.get(0)
                    .getAsJsonObject()
                    .getAsJsonArray("alternatives")
                    .get(0)
                    .getAsJsonObject();
            JsonElement transcript = alternative.get("transcript");
            if (transcript == null || transcript.getAsString().isBlank()) {
                throw failed();
            }
            return transcript.getAsString().strip();
        } catch (RuntimeException e) {
            throw failed();
        }
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
