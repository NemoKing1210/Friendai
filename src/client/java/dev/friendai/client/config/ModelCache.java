package dev.friendai.client.config;

import dev.friendai.agent.llm.provider.ModelListCache;
import java.util.List;

/** Model lists fetched from providers, kept for a day with a last-good fallback when the provider is offline. */
public final class ModelCache {
    private static final ModelListCache CACHE = new ModelListCache();

    private ModelCache() {}

    public static List<String> get(String provider) {
        return CACHE.get(provider, System.currentTimeMillis());
    }

    public static void put(String provider, List<String> models) {
        CACHE.put(provider, models, System.currentTimeMillis());
    }
}
