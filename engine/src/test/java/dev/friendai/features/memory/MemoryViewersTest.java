package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryViewersTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void freshViewerNeedsUpdate() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        assertTrue(viewers.isOpen(A));
        assertTrue(viewers.needsUpdate(A, 0));
        assertTrue(viewers.needsUpdate(A, 7));
    }

    @Test
    void markSentThenSameRevisionNeedsNothing() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.markSent(A, 3);
        assertFalse(viewers.needsUpdate(A, 3));
    }

    @Test
    void newRevisionNeedsUpdate() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.markSent(A, 3);
        assertTrue(viewers.needsUpdate(A, 4));
    }

    @Test
    void closedViewerNeverNeedsUpdate() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.markSent(A, 3);
        viewers.close(A);
        assertFalse(viewers.isOpen(A));
        assertFalse(viewers.needsUpdate(A, 4));
        assertFalse(viewers.viewers().contains(A));
    }

    @Test
    void reopenResetsSentRevision() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.markSent(A, 3);
        viewers.close(A);
        viewers.open(A);
        assertTrue(viewers.needsUpdate(A, 3));
    }

    @Test
    void viewersAreTheOpenOnesOnly() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.open(B);
        viewers.close(A);
        assertTrue(viewers.viewers().contains(B));
        assertFalse(viewers.viewers().contains(A));
    }

    @Test
    void aNewEpochNeedsUpdateEvenWithTheSameRevision() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.markSent(A, 3, 0);
        assertFalse(viewers.needsUpdate(A, 3, 0));
        assertTrue(viewers.needsUpdate(A, 3, 1));
        viewers.markSent(A, 3, 1);
        assertFalse(viewers.needsUpdate(A, 3, 1));
    }

    @Test
    void markSentOnlyAppliesToOnePlayer() {
        MemoryViewers viewers = new MemoryViewers();
        viewers.open(A);
        viewers.open(B);
        viewers.markSent(A, 5);
        assertFalse(viewers.needsUpdate(A, 5));
        assertTrue(viewers.needsUpdate(B, 5));
    }
}
