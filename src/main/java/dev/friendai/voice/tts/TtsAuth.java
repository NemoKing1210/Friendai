package dev.friendai.voice.tts;

import java.util.Map;

/** How a speech provider expects its API key. */
public enum TtsAuth {
    BEARER("Authorization"),
    XI_API_KEY("xi-api-key"),
    NONE(null);

    private final String header;

    TtsAuth(String header) {
        this.header = header;
    }

    /** Puts the key in the provider's header; an absent or empty key adds nothing. */
    public void apply(Map<String, String> headers, String key) {
        if (this == NONE || key == null || key.isBlank()) {
            return;
        }
        headers.put(header, this == BEARER ? "Bearer " + key : key);
    }
}
