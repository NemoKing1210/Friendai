package dev.friendai.features.knowledge.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-owner search hints. Runtime state only: a hint is short-lived advice, not knowledge to persist. */
public final class SearchHintStore {
    private static final int MAX_OWNERS = 128;
    private final Map<UUID, SearchHint> hints = new LinkedHashMap<>();

    public synchronized void put(UUID owner, SearchHint hint) {
        if (owner == null || hint == null) return;
        hints.put(owner, hint);
        while (hints.size() > MAX_OWNERS) {
            hints.entrySet()
                    .remove(hints.entrySet().stream()
                            .min(Comparator.comparingLong(
                                    entry -> entry.getValue().createdTick()))
                            .map(Map.Entry::getKey)
                            .orElseThrow());
        }
    }

    /** The live hint, or empty when there is none or it has expired (an expired hint is dropped). */
    public synchronized Optional<SearchHint> get(UUID owner, long now) {
        SearchHint hint = hints.get(owner);
        if (hint == null) return Optional.empty();
        if (hint.expired(now)) {
            hints.remove(owner);
            return Optional.empty();
        }
        return Optional.of(hint);
    }

    public synchronized void clear(UUID owner) {
        if (owner != null) hints.remove(owner);
    }

    public synchronized int size() {
        return hints.size();
    }
}
