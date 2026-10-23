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
    private record Sent(Long revision, int epoch) {}

    private final Map<UUID, Sent> lastSent = new HashMap<>();

    /** Registers a viewer and forgets any revision sent before. */
    public void open(UUID player) {
        lastSent.put(player, new Sent(null, 0));
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
        return needsUpdate(player, revision, 0);
    }

    /**
     * Like {@link #needsUpdate(UUID, long)}, but also refreshes when the world around the book changed
     * ({@code epoch}), such as a player going online or offline, since that bumps no book revision.
     */
    public boolean needsUpdate(UUID player, long revision, int epoch) {
        Sent sent = lastSent.get(player);
        return sent != null && (sent.revision() == null || sent.revision() != revision || sent.epoch() != epoch);
    }

    public void markSent(UUID player, long revision) {
        markSent(player, revision, 0);
    }

    public void markSent(UUID player, long revision, int epoch) {
        if (lastSent.containsKey(player)) {
            lastSent.put(player, new Sent(revision, epoch));
        }
    }
}
