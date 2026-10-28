package dev.friendai.foundation.limits;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    @Test
    void secondCallInARowIsDenied() {
        AtomicLong now = new AtomicLong(1_000);
        RateLimiter limiter = new RateLimiter(1_000, now::get);
        UUID player = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player));
        assertFalse(limiter.tryAcquire(player));
    }

    @Test
    void allowedAgainAfterTheInterval() {
        AtomicLong now = new AtomicLong(1_000);
        RateLimiter limiter = new RateLimiter(1_000, now::get);
        UUID player = UUID.randomUUID();
        limiter.tryAcquire(player);
        now.addAndGet(999);
        assertFalse(limiter.tryAcquire(player));
        now.addAndGet(1);
        assertTrue(limiter.tryAcquire(player));
    }

    @Test
    void playersAreIndependent() {
        RateLimiter limiter = new RateLimiter(1_000, () -> 5_000);
        assertTrue(limiter.tryAcquire(UUID.randomUUID()));
        assertTrue(limiter.tryAcquire(UUID.randomUUID()));
    }
}
