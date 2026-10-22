package dev.friedai.llm.provider;

import java.util.Map;

public enum AuthStyle {
    BEARER,
    X_API_KEY,
    GOOG_API_KEY,
    NONE;

    /** Adds the auth header for this style. Adds nothing for an empty key. */
    public void apply(Map<String, String> headers, String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return;
        }
        switch (this) {
            case BEARER -> headers.put("Authorization", "Bearer " + apiKey);
            case X_API_KEY -> headers.put("x-api-key", apiKey);
            case GOOG_API_KEY -> headers.put("x-goog-api-key", apiKey);
            case NONE -> {}
        }
    }
}
