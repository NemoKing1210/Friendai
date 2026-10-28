package dev.friendai.features.speech;

import java.util.Map;

/** How a provider expects its API key. Keys always travel in headers, never in URLs. */
public enum VoiceAuth {
    NONE,
    BEARER,
    ELEVENLABS,
    YANDEX;

    /** @param apiKey resolved key; blank means no header is sent at all */
    public Map<String, String> headers(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Map.of();
        }
        return switch (this) {
            case NONE -> Map.of();
            case BEARER -> Map.of("Authorization", "Bearer " + apiKey);
            case ELEVENLABS -> Map.of("xi-api-key", apiKey);
            case YANDEX -> Map.of("Authorization", "Api-Key " + apiKey);
        };
    }
}
