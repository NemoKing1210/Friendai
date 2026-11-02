package dev.friendai.features.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PcmRingBufferTest {
    @Test
    void keepsTheNewestBytesWhenFull() {
        PcmRingBuffer ring = new PcmRingBuffer(4);
        ring.write(new byte[] {1, 2, 3}, 3);
        ring.write(new byte[] {4, 5, 6}, 3);
        assertArrayEquals(new byte[] {3, 4, 5, 6}, ring.snapshot());
        assertEquals(4, ring.size());
    }

    @Test
    void anOversizedWriteKeepsItsTail() {
        PcmRingBuffer ring = new PcmRingBuffer(3);
        ring.write(new byte[] {1, 2, 3, 4, 5}, 5);
        assertArrayEquals(new byte[] {3, 4, 5}, ring.snapshot());
    }

    @Test
    void clearDropsEverythingAndSnapshotIsACopy() {
        PcmRingBuffer ring = new PcmRingBuffer(4);
        ring.write(new byte[] {1, 2}, 2);
        byte[] copy = ring.snapshot();
        copy[0] = 9;
        assertArrayEquals(new byte[] {1, 2}, ring.snapshot());
        ring.clear();
        assertEquals(0, ring.size());
        assertArrayEquals(new byte[0], ring.snapshot());
    }

    @Test
    void boundsAndNoOpsAreExplicit() {
        PcmRingBuffer ring = new PcmRingBuffer(2);
        ring.write(null, 4);
        ring.write(new byte[] {1}, 0);
        assertEquals(0, ring.size());
        try {
            new PcmRingBuffer(0);
            throw new AssertionError("capacity must be positive");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
