package dev.friedai.client.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Model lists fetched from providers, kept for the current game session. */
public final class ModelCache {
    private static final Map<String, List<String>> MODELS = new HashMap<>();

    private ModelCache() {}

    public static synchronized List<String> get(String provider) {
        return MODELS.getOrDefault(provider, List.of());
    }

    public static synchronized void put(String provider, List<String> models) {
        MODELS.put(provider, List.copyOf(models));
    }
}
