package dev.friendai.voice.tts;

import dev.friendai.text.UserFacingException;
import dev.friendai.text.UserMessage;
import dev.friendai.voice.PcmAudio;
import dev.friendai.voice.PreparedRequest;
import dev.friendai.voice.ResolvedTts;
import dev.friendai.voice.VoiceAuth;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Yandex SpeechKit's synthesis endpoint: a form body and raw 16 kHz PCM back. */
public final class YandexTtsProtocol implements TtsProtocol {
    public static final String ID = "yandex_tts";
    public static final int SAMPLE_RATE = 16000;

    @Override
    public PreparedRequest prepare(ResolvedTts settings, String text) {
        if (settings.language() == null || settings.language().isBlank()) {
            throw new UserFacingException(UserMessage.of("friendai.voice.error.unsupported_language"));
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("text", text);
        form.put("lang", settings.language());
        form.put("voice", settings.voice());
        form.put("format", "lpcm");
        form.put("sampleRateHertz", String.valueOf(SAMPLE_RATE));
        form.put("speed", String.valueOf(settings.speed()));
        if (settings.folderId() != null && !settings.folderId().isBlank()) {
            form.put("folderId", settings.folderId());
        }
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
        URI uri = OpenAiTtsProtocol.join(settings.baseUrl(), "/speech/v1/tts:synthesize");
        return new PreparedRequest(
                uri,
                VoiceAuth.YANDEX.headers(settings.apiKey()),
                body.toString().getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded");
    }

    @Override
    public PcmAudio parse(byte[] body, ResolvedTts settings) {
        if (body == null || body.length == 0) {
            throw new UserFacingException(UserMessage.of("friendai.voice.tts.error.failed"));
        }
        return new PcmAudio(body, SAMPLE_RATE);
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
