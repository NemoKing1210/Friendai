package dev.friendai.features.speech.tts;

import dev.friendai.features.speech.CloudField;
import dev.friendai.features.speech.VoiceAuth;
import dev.friendai.features.speech.VoicePreset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Speech-synthesis providers available to a client. */
public final class TtsPresets {
    public static final String LOCAL = "local";
    /** The OpenAI preset's base URL; a blank configured base URL falls back to it. */
    public static final String OPENAI_DEFAULT_BASE = "https://api.openai.com/v1";
    /** The ElevenLabs preset's base URL; a blank configured base URL falls back to it. */
    public static final String ELEVENLABS_DEFAULT_BASE = "https://api.elevenlabs.io";

    private static final List<String> OPENAI_VOICES = List.of(
            "alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "verse", "marin",
            "cedar");
    private static final List<String> YANDEX_VOICES =
            List.of("alena", "filipp", "ermil", "jane", "omazh", "zahar", "madirus");

    private static final List<VoicePreset> ALL = List.of(
            new VoicePreset(
                    LOCAL,
                    "Local (Piper)",
                    "local",
                    null,
                    VoiceAuth.NONE,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    List.of(),
                    Set.of()),
            new VoicePreset(
                    "openai",
                    "OpenAI",
                    "openai_speech",
                    OPENAI_DEFAULT_BASE,
                    VoiceAuth.BEARER,
                    "gpt-4o-mini-tts",
                    "alloy",
                    null,
                    "env:OPENAI_API_KEY",
                    true,
                    "https://platform.openai.com/api-keys",
                    OPENAI_VOICES,
                    Set.of(CloudField.API_KEY, CloudField.MODEL, CloudField.VOICE, CloudField.BASE_URL)),
            new VoicePreset(
                    "google",
                    "Google Cloud TTS",
                    "google_tts",
                    "https://texttospeech.googleapis.com",
                    VoiceAuth.GOOGLE,
                    null,
                    "en-US-Neural2-F",
                    "en-US",
                    "env:GOOGLE_API_KEY",
                    true,
                    "https://console.cloud.google.com/apis/credentials",
                    List.of(
                            "en-US-Neural2-F",
                            "en-GB-Neural2-A",
                            "de-DE-Neural2-F",
                            "fr-FR-Neural2-A",
                            "es-ES-Neural2-A",
                            "pt-BR-Neural2-A",
                            "zh-CN-Neural2-A"),
                    Set.of(CloudField.API_KEY, CloudField.VOICE, CloudField.LANGUAGE, CloudField.BASE_URL)),
            new VoicePreset(
                    "elevenlabs",
                    "ElevenLabs",
                    "elevenlabs_tts",
                    ELEVENLABS_DEFAULT_BASE,
                    VoiceAuth.ELEVENLABS,
                    "eleven_multilingual_v2",
                    null,
                    null,
                    "env:ELEVENLABS_API_KEY",
                    true,
                    "https://elevenlabs.io/app/settings/api-keys",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.MODEL, CloudField.VOICE)),
            new VoicePreset(
                    "yandex",
                    "Yandex SpeechKit",
                    "yandex_tts",
                    "https://tts.api.cloud.yandex.net",
                    VoiceAuth.YANDEX,
                    null,
                    "alena",
                    "ru-RU",
                    "env:YANDEX_API_KEY",
                    true,
                    "https://yandex.cloud/ru/docs/speechkit/",
                    YANDEX_VOICES,
                    Set.of(CloudField.API_KEY, CloudField.VOICE, CloudField.FOLDER_ID, CloudField.LANGUAGE)));

    private TtsPresets() {}

    public static List<VoicePreset> all() {
        return ALL;
    }

    public static List<String> ids() {
        return ALL.stream().map(VoicePreset::id).toList();
    }

    public static Optional<VoicePreset> byId(String id) {
        return ALL.stream().filter(preset -> preset.id().equals(id)).findFirst();
    }

    /** The preset for an id; an unknown id falls back to local rather than failing a turn. */
    public static VoicePreset require(String id) {
        return byId(id).orElse(local());
    }

    public static VoicePreset local() {
        return byId(LOCAL).orElseThrow();
    }
}
