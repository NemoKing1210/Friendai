package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.persistence.StoreFailureKind;
import dev.friendai.foundation.persistence.StoreReadOnlyReason;
import dev.friendai.foundation.persistence.StoreWriteStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreHealthTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static Map<String, Map<UUID, StoreWriteStatus>> sources(StoreWriteStatus status) {
        return Map.of("goals", Map.of(OWNER, status));
    }

    @Test
    void aFailureIsReportedOnceAndItsRecoveryOnce() {
        StoreHealth health = new StoreHealth();
        StoreWriteStatus failed = new StoreWriteStatus(4, 3, true, StoreFailureKind.WRITE_FAILED, null);

        List<StoreHealth.Entry> first = health.transitions(sources(failed));
        assertEquals(1, first.size(), "a failure is news: " + first);
        assertTrue(first.get(0).line().contains("failure=" + StoreFailureKind.WRITE_FAILED));
        assertFalse(first.get(0).line().contains("/home/"), "no path in a health line");

        assertTrue(health.transitions(sources(failed)).isEmpty(), "the same failure is not repeated");

        List<StoreHealth.Entry> recovered = health.transitions(sources(new StoreWriteStatus(4, 4, false, null, null)));
        assertEquals(1, recovered.size(), "a recovery is reported once: " + recovered);
        assertTrue(health.transitions(sources(new StoreWriteStatus(4, 4, false, null, null)))
                .isEmpty());
    }

    @Test
    void aHealthyStoreIsNotNewsButAReadOnlyOneIs() {
        StoreHealth health = new StoreHealth();
        assertTrue(
                health.transitions(sources(new StoreWriteStatus(1, 1, false, null, null)))
                        .isEmpty(),
                "a first sighting of a healthy store says nothing");
        List<StoreHealth.Entry> protectedOnce =
                health.transitions(sources(new StoreWriteStatus(1, 1, false, null, StoreReadOnlyReason.CORRUPT)));
        assertEquals(1, protectedOnce.size());
        assertTrue(protectedOnce.get(0).line().contains("read-only=" + StoreReadOnlyReason.CORRUPT));
    }

    @Test
    void theSummaryNamesTheStoreTheRevisionAndTheReasonOnly() {
        Map<String, Map<UUID, StoreWriteStatus>> sources = Map.of(
                "goals",
                Map.of(OWNER, new StoreWriteStatus(7, 5, true, StoreFailureKind.WRITE_FAILED, null)),
                "terrain",
                Map.of(UUID.randomUUID(), new StoreWriteStatus(1, 1, false, null, null)));
        String summary = StoreHealth.summary(sources, OWNER);
        assertTrue(summary.contains("goals"), summary);
        assertTrue(summary.contains("saved=5"), summary);
        assertTrue(summary.contains("failure=" + StoreFailureKind.WRITE_FAILED), summary);
        assertFalse(summary.contains("terrain"), "another owner's store is not in this owner's summary: " + summary);
    }
}
