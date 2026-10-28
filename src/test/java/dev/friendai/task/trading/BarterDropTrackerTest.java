package dev.friendai.task.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BarterDropTrackerTest {
    @Test
    void recordsOnlyNewDropsOwnedByTheExactPiglinDuringTheAttempt() {
        var tracker = new BarterDropTracker();
        UUID attempt = UUID.randomUUID();
        UUID piglin = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID output = UUID.randomUUID();
        tracker.begin(attempt, piglin, 20);

        assertFalse(tracker.observeVanillaOutput(attempt, other, UUID.randomUUID(), 21));
        assertFalse(tracker.observeVanillaOutput(attempt, piglin, UUID.randomUUID(), 19));
        assertFalse(
                tracker.observeVanillaOutput(UUID.randomUUID(), piglin, UUID.randomUUID(), 21),
                "another attempt cannot claim the exact piglin's output");
        assertTrue(tracker.observeVanillaOutput(attempt, piglin, output, 21));
        assertFalse(
                tracker.observeVanillaOutput(attempt, piglin, output, 22), "one item entity must not be counted twice");
        assertEquals(java.util.List.of(output), tracker.outputs(attempt));
        tracker.finish(attempt);
        assertEquals(java.util.List.of(), tracker.outputs(attempt));
    }

    @Test
    void permitsOnlyOneLiveAttemptPerPiglinAndBoundsAttempts() {
        var tracker = new BarterDropTracker();
        UUID piglin = UUID.randomUUID();
        tracker.begin(UUID.randomUUID(), piglin, 0);
        assertThrows(IllegalStateException.class, () -> tracker.begin(UUID.randomUUID(), piglin, 1));
    }
}
