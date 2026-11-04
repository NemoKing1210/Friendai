package dev.friendai.presentation.companion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One expensive open per owner, kind and server tick. A client may legitimately repeat an open request after a lost
 * reply, but building and encoding the same snapshot several times in one tick spends server time for nothing; the
 * cheap closes stay unlimited.
 */
public final class MenuOpenCoalescer {
    private static final int MAX_ENTRIES = 512;

    private final Map<String, Long> lastBuilt = new HashMap<>();

    /** Whether the snapshot for this owner and kind may be built on this tick; false means it was already built. */
    public boolean mayBuild(String kind, UUID owner, long tick) {
        String key = kind + "/" + owner;
        Long last = lastBuilt.get(key);
        if (last != null && last == tick) {
            return false;
        }
        if (lastBuilt.size() >= MAX_ENTRIES) {
            lastBuilt.clear();
        }
        lastBuilt.put(key, tick);
        return true;
    }

    /** Drops an owner's entries when it disconnects; a reconnect starts with a fresh view. */
    public void forget(UUID owner) {
        lastBuilt.keySet().removeIf(key -> key.endsWith("/" + owner));
    }
}
