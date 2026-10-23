package dev.friendai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingTrustTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID OTHER_TARGET = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    private final PendingTrust pending = new PendingTrust();

    @Test
    void openThenCurrent() {
        TrustRequest request = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        assertEquals(1, request.id());
        assertEquals(1_000 + PendingTrust.TTL_MILLIS, request.expiresAtMillis());
        assertEquals(request, pending.current(OWNER, 1_000).orElseThrow());
    }

    @Test
    void newRequestReplacesOld() {
        TrustRequest first = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        TrustRequest second = pending.open(OWNER, OTHER_TARGET, "Ann", false, 2_000);
        assertNotEquals(first.id(), second.id());
        assertEquals(second, pending.current(OWNER, 2_000).orElseThrow());
    }

    @Test
    void takeWithMatchingId() {
        TrustRequest request = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        assertEquals(request, pending.take(OWNER, request.id(), 1_000).orElseThrow());
        assertTrue(pending.current(OWNER, 1_000).isEmpty());
    }

    @Test
    void staleIdDoesNotTake() {
        TrustRequest first = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        TrustRequest second = pending.open(OWNER, OTHER_TARGET, "Ann", true, 1_001);
        assertTrue(pending.take(OWNER, first.id(), 1_001).isEmpty());
        assertEquals(second, pending.current(OWNER, 1_001).orElseThrow());
    }

    @Test
    void expiresAfterTtl() {
        pending.open(OWNER, TARGET, "Bob", true, 1_000);
        assertTrue(pending.current(OWNER, 1_000 + PendingTrust.TTL_MILLIS + 1).isEmpty());
    }

    @Test
    void expireReturnsAndRemoves() {
        TrustRequest old = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        TrustRequest fresh = pending.open(OTHER_OWNER, OTHER_TARGET, "Ann", true, 1_000
                + PendingTrust.TTL_MILLIS);
        List<TrustRequest> expired = pending.expire(1_000 + PendingTrust.TTL_MILLIS + 1);
        assertEquals(List.of(old), expired);
        assertTrue(pending.current(OWNER, 1_000 + PendingTrust.TTL_MILLIS + 1).isEmpty());
        assertEquals(fresh, pending.current(OTHER_OWNER, 1_000 + PendingTrust.TTL_MILLIS + 1).orElseThrow());
    }

    @Test
    void ownersAreIndependent() {
        TrustRequest mine = pending.open(OWNER, TARGET, "Bob", true, 1_000);
        TrustRequest theirs = pending.open(OTHER_OWNER, OTHER_TARGET, "Ann", false, 1_000);
        assertEquals(mine, pending.current(OWNER, 1_000).orElseThrow());
        assertEquals(theirs, pending.current(OTHER_OWNER, 1_000).orElseThrow());
        assertEquals(mine, pending.takeCurrent(OWNER, 1_000).orElseThrow());
        assertTrue(pending.current(OWNER, 1_000).isEmpty());
        assertEquals(theirs, pending.current(OTHER_OWNER, 1_000).orElseThrow());
    }
}
