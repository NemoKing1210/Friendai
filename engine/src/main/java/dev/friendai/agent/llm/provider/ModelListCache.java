package dev.friendai.agent.llm.provider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model lists with a time-to-live and a last-good fallback, so a provider outage never empties a picker and a
 * successful fetch is not repeated on every screen open. Keyed by a non-secret identity the caller builds.
 */
public final class ModelListCache {
    public static final long DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L;

    private final long ttlMs;
    private final Map<String, Fetched> fetched = new HashMap<>();
    private final Map<String, List<String>> lastGood = new HashMap<>();

    public ModelListCache() {
        this(DEFAULT_TTL_MS);
    }

    public ModelListCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public List<String> get(String key, long now) {
        Fetched entry = fetched.get(key);
        if (entry != null && now - entry.fetchedAt < ttlMs && now >= entry.fetchedAt) {
            return entry.models;
        }
        return lastGood.getOrDefault(key, List.of());
    }

    public boolean isFresh(String key, long now) {
        Fetched entry = fetched.get(key);
        return entry != null && now >= entry.fetchedAt && now - entry.fetchedAt < ttlMs;
    }

    /** An empty answer is a provider failure, not a new catalogue: it never replaces the last good list. */
    public void put(String key, List<String> models, long now) {
        if (models.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(models);
        fetched.put(key, new Fetched(copy, now));
        lastGood.put(key, copy);
    }

    /** The last good lists, for a client that keeps them across restarts. */
    public Map<String, List<String>> lastGood() {
        return Map.copyOf(lastGood);
    }

    /** Restores persisted lists as stale entries; a fresh fetch replaces them. */
    public void restore(Map<String, List<String>> saved) {
        saved.forEach((key, models) -> {
            if (key != null && models != null && !models.isEmpty()) {
                lastGood.put(key, List.copyOf(models));
            }
        });
    }

    private record Fetched(List<String> models, long fetchedAt) {}
}
