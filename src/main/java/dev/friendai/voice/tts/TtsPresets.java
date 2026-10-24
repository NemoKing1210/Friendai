package dev.friendai.voice.tts;

import java.util.List;
import java.util.Optional;

/** The cloud speech providers the mod knows about. */
public final class TtsPresets {
    public static final String OPENAI_DEFAULT_BASE = "https://api.openai.com/v1";
    public static final String ELEVENLABS_DEFAULT_BASE = "https://api.elevenlabs.io";

    private static final List<TtsProvider> ALL = List.of(
            new TtsProvider(
                    "openai",
                    "OpenAI",
                    OPENAI_DEFAULT_BASE,
                    TtsAuth.BEARER,
                    "gpt-4o-mini-tts",
                    "alloy",
                    "https://platform.openai.com/docs/guides/text-to-speech",
                    true,
                    true),
            new TtsProvider(
                    "elevenlabs",
                    "ElevenLabs",
                    ELEVENLABS_DEFAULT_BASE,
                    TtsAuth.XI_API_KEY,
                    "eleven_multilingual_v2",
                    "21m00Tcm4TlvDq8ikWAM",
                    "https://elevenlabs.io/docs/api-reference/text-to-speech",
                    true,
                    false));

    private TtsPresets() {}

    public static List<TtsProvider> all() {
        return ALL;
    }

    public static Optional<TtsProvider> byId(String id) {
        return ALL.stream().filter(provider -> provider.id().equals(id)).findFirst();
    }
}
