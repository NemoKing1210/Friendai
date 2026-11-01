package dev.friendai.features.speech.tts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.features.speech.PcmAudio;
import dev.friendai.features.speech.PreparedRequest;
import dev.friendai.features.speech.ResolvedTts;
import dev.friendai.features.speech.VoiceAuth;
import dev.friendai.features.speech.WavCodec;
import dev.friendai.foundation.text.UserFacingException;
import dev.friendai.foundation.text.UserMessage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Google Cloud Text-to-Speech: a JSON request, an API key header and base64 LINEAR16 audio back. */
public final class GoogleTtsProtocol implements TtsProtocol {
    public static final String ID = "google_tts";
    public static final int SAMPLE_RATE = 24000;

    @Override
    public PreparedRequest prepare(ResolvedTts settings, String text) {
        String language = settings.language() == null || settings.language().isBlank() ? "en-US" : settings.language();
        String voice = settings.voice() == null || settings.voice().isBlank() ? "en-US-Neural2-F" : settings.voice();
        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        JsonObject voiceObject = new JsonObject();
        voiceObject.addProperty("languageCode", language);
        voiceObject.addProperty("name", voice);
        JsonObject audio = new JsonObject();
        audio.addProperty("audioEncoding", "LINEAR16");
        audio.addProperty("sampleRateHertz", SAMPLE_RATE);
        audio.addProperty("speakingRate", settings.speed());
        JsonObject body = new JsonObject();
        body.add("input", input);
        body.add("voice", voiceObject);
        body.add("audioConfig", audio);
        return new PreparedRequest(
                OpenAiTtsProtocol.join(settings.baseUrl(), "/v1/text:synthesize"),
                VoiceAuth.GOOGLE.headers(settings.apiKey()),
                body.toString().getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    @Override
    public PcmAudio parse(byte[] body, ResolvedTts settings) {
        try {
            String content = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .get("audioContent")
                    .getAsString();
            byte[] audio = Base64.getDecoder().decode(content);
            try {
                TtsClip clip = WavCodec.decodeClip(audio);
                return new PcmAudio(clip.pcm16le(), clip.sampleRate());
            } catch (IllegalArgumentException notWav) {
                // LINEAR16 may arrive without a WAV header; the requested sample rate is known.
                return new PcmAudio(audio, SAMPLE_RATE);
            }
        } catch (RuntimeException e) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
    }
}
