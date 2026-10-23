package dev.friendai.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who has the memory tab open and which book revision they last received. Not thread-safe: the server
 * thread owns it, like the books it describes.
 */
public final class MemoryViewers {
    private final Map<UUID, Long> lastSent = new HashMap<>();

    /** Registers a viewer and forgets any revision sent before. */
    public void open(UUID player) {
        lastSent.put(player, null);
    }

    public void close(UUID player) {
        lastSent.remove(player);
    }

    public Set<UUID> viewers() {
        return Set.copyOf(lastSent.keySet());
    }

    public boolean isOpen(UUID player) {
        return lastSent.containsKey(player);
    }

    /** True for an open viewer whose last sent revision differs from {@code revision} or that got nothing yet. */
    public boolean needsUpdate(UUID player, long revision) {
        if (!lastSent.containsKey(player)) {
            return false;
        }
        Long sent = lastSent.get(player);
        return sent == null || sent != revision;
    }

    public void markSent(UUID player, long revision) {
        if (lastSent.containsKey(player)) {
            lastSent.put(player, revision);
        }
    }
}
