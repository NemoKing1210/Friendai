package dev.friendai.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Allows one action per key every {@code minIntervalMs}. */
public final class RateLimiter {
    private final long minIntervalMs;
    private final LongSupplier clock;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    public RateLimiter(long minIntervalMs, LongSupplier clock) {
        this.minIntervalMs = minIntervalMs;
        this.clock = clock;
    }

    public boolean tryAcquire(UUID key) {
        long now = clock.getAsLong();
        boolean[] allowed = {false};
        last.compute(key, (k, previous) -> {
            if (previous == null || now - previous >= minIntervalMs) {
                allowed[0] = true;
                return now;
            }
            return previous;
        });
        return allowed[0];
    }
}
