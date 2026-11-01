package dev.friendai.features.speech.stt;

import dev.friendai.features.speech.CloudField;
import dev.friendai.features.speech.VoiceAuth;
import dev.friendai.features.speech.VoicePreset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Speech-recognition providers available to a client. */
public final class SttPresets {
    public static final String LOCAL = "local";

    private static final List<VoicePreset> ALL = List.of(
            new VoicePreset(
                    LOCAL,
                    "Local (whisper.cpp)",
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
                    "openai_transcriptions",
                    "https://api.openai.com/v1",
                    VoiceAuth.BEARER,
                    "gpt-transcribe",
                    null,
                    null,
                    "env:OPENAI_API_KEY",
                    true,
                    "https://platform.openai.com/api-keys",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.MODEL, CloudField.BASE_URL)),
            new VoicePreset(
                    "google",
                    "Google Cloud STT",
                    "google_stt",
                    "https://speech.googleapis.com",
                    VoiceAuth.GOOGLE,
                    null,
                    null,
                    "en-US",
                    "env:GOOGLE_API_KEY",
                    true,
                    "https://console.cloud.google.com/apis/credentials",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.LANGUAGE, CloudField.BASE_URL)),
            new VoicePreset(
                    "deepgram",
                    "Deepgram",
                    "deepgram_stt",
                    "https://api.deepgram.com",
                    VoiceAuth.DEEPGRAM,
                    "nova-3",
                    null,
                    null,
                    "env:DEEPGRAM_API_KEY",
                    true,
                    "https://console.deepgram.com/",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.MODEL, CloudField.LANGUAGE, CloudField.BASE_URL)),
            new VoicePreset(
                    "groq",
                    "Groq",
                    "openai_transcriptions",
                    "https://api.groq.com/openai/v1",
                    VoiceAuth.BEARER,
                    "whisper-large-v3",
                    null,
                    null,
                    "env:GROQ_API_KEY",
                    true,
                    "https://console.groq.com/keys",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.MODEL, CloudField.BASE_URL)),
            new VoicePreset(
                    "yandex",
                    "Yandex SpeechKit",
                    "yandex_stt",
                    "https://stt.api.cloud.yandex.net",
                    VoiceAuth.YANDEX,
                    null,
                    null,
                    null,
                    "env:YANDEX_API_KEY",
                    true,
                    "https://yandex.cloud/ru/docs/speechkit/",
                    List.of(),
                    Set.of(CloudField.API_KEY, CloudField.FOLDER_ID)));

    private SttPresets() {}

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
