package dev.friendai.features.speech.stt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedStt;
import dev.friendai.features.speech.VoiceAuth;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Deepgram's prerecorded endpoint: raw WAV in the body, a token header and a transcript in a nested JSON reply. */
public final class DeepgramSttProtocol implements SttProtocol {
    public static final String ID = "deepgram_stt";

    @Override
    public PreparedRequest prepare(ResolvedStt settings, byte[] wav16k, byte[] pcm16k) {
        StringBuilder query = new StringBuilder("?smart_format=true");
        String model = settings.model();
        if (model != null && !model.isBlank()) {
            query.append("&model=").append(encode(model));
        }
        String language = settings.language();
        if (language != null && !language.isBlank() && !language.equalsIgnoreCase("auto")) {
            query.append("&language=").append(encode(language));
        }
        URI uri = URI.create(
                OpenAiSttProtocol.join(settings.baseUrl(), "/v1/listen").toString() + query);
        return new PreparedRequest(uri, VoiceAuth.DEEPGRAM.headers(settings.apiKey()), wav16k, "audio/wav");
    }

    @Override
    public String parse(JsonObject response) {
        try {
            JsonArray channels = response.getAsJsonObject("results").getAsJsonArray("channels");
            JsonObject first = channels.get(0).getAsJsonObject();
            JsonArray alternatives = first.getAsJsonArray("alternatives");
            String transcript =
                    alternatives.get(0).getAsJsonObject().get("transcript").getAsString();
            if (transcript.isBlank()) {
                throw failed();
            }
            return transcript.strip();
        } catch (RuntimeException e) {
            throw failed();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static UserFacingException failed() {
        return new UserFacingException(UserMessage.of("friendai.voice.error.failed"));
    }
}
