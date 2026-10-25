package dev.friendai.voice.tts;

import com.google.gson.JsonObject;
import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceAuth;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** ElevenLabs' text-to-speech endpoint with a raw PCM output format. */
public final class ElevenLabsTtsProtocol implements TtsProtocol {
    public static final String ID = "elevenlabs_tts";
    public static final int SAMPLE_RATE = 24000;

    @Override
    public PreparedRequest prepare(ResolvedTts settings, String text) {
        if (settings.voice() == null || settings.voice().isBlank()) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.no_voice"));
        }
        JsonObject voiceSettings = new JsonObject();
        voiceSettings.addProperty("speed", settings.speed());
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", settings.model());
        body.add("voice_settings", voiceSettings);
        Map<String, String> headers = new HashMap<>(VoiceAuth.ELEVENLABS.headers(settings.apiKey()));
        String voice = URLEncoder.encode(settings.voice(), StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = OpenAiTtsProtocol.join(settings.baseUrl(),
                "/v1/text-to-speech/" + voice + "?output_format=pcm_24000");
        return new PreparedRequest(uri, headers, body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    @Override
    public PcmAudio parse(byte[] body, ResolvedTts settings) {
        if (body == null || body.length == 0) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
        return new PcmAudio(body, SAMPLE_RATE);
    }
}
