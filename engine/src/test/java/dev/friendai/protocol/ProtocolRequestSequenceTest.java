package dev.friendai.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProtocolRequestSequenceTest {
    @Test
    void issuesPositiveIncreasingRequestIds() {
        ProtocolRequestSequence sequence = new ProtocolRequestSequence();

        assertEquals(1, sequence.nextRequestId());
        assertEquals(2, sequence.nextRequestId());
        assertEquals(3, sequence.nextRequestId());
    }

    @Test
    void rejectsZeroDuplicateAndStaleIncomingIdsWithoutAdvancingPastThem() {
        ProtocolRequestSequence sequence = new ProtocolRequestSequence();

        assertFalse(sequence.acceptIncoming(0));
        assertTrue(sequence.acceptIncoming(1));
        assertFalse(sequence.acceptIncoming(1));
        assertTrue(sequence.acceptIncoming(3));
        assertFalse(sequence.acceptIncoming(2));
        assertTrue(sequence.acceptIncoming(4));
    }
}
