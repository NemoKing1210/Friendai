package dev.friendai.foundation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.movement.OrbitCoverage.State;
import org.junit.jupiter.api.Test;

class OrbitCoverageTest {
    @Test
    void reachedWaypointsCountAsCoverage() {
        OrbitCoverage coverage = new OrbitCoverage(8);
        for (int index = 0; index < 8; index++) coverage.resolve(index, State.REACHED);

        assertEquals(8, coverage.verified());
        assertEquals(8, coverage.reached());
        assertEquals(0, coverage.blocked());
        assertTrue(coverage.complete());
    }

    @Test
    void aBlockedWaypointIsNotCoverage() {
        OrbitCoverage coverage = new OrbitCoverage(8);
        coverage.resolve(0, State.REACHED);
        coverage.resolve(1, State.BLOCKED);
        coverage.resolve(2, State.BYPASSED);

        assertEquals(2, coverage.verified());
        assertEquals(1, coverage.bypassed());
        assertEquals(1, coverage.blocked());
        assertEquals(5, coverage.remaining());
        assertFalse(coverage.complete());
    }

    @Test
    void aWaypointCannotBeCountedTwice() {
        OrbitCoverage coverage = new OrbitCoverage(2);
        assertTrue(coverage.resolve(0, State.BYPASSED));

        assertFalse(coverage.resolve(0, State.REACHED));
        assertEquals(1, coverage.verified());
    }

    @Test
    void anOutOfRangeIndexIsRefused() {
        OrbitCoverage coverage = new OrbitCoverage(2);

        assertFalse(coverage.resolve(2, State.REACHED));
        assertFalse(coverage.resolve(-1, State.REACHED));
    }

    @Test
    void aPendingStateCannotBeRecordedAsAnOutcome() {
        OrbitCoverage coverage = new OrbitCoverage(2);

        assertThrows(IllegalArgumentException.class, () -> coverage.resolve(0, State.PENDING));
        assertThrows(IllegalArgumentException.class, () -> new OrbitCoverage(0));
    }
}
