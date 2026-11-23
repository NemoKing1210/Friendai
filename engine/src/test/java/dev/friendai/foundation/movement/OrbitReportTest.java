package dev.friendai.foundation.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.movement.OrbitCoverage.State;
import org.junit.jupiter.api.Test;

class OrbitReportTest {
    @Test
    void aVerifiedOrbitNamesItsWaypointCount() {
        OrbitCoverage coverage = new OrbitCoverage(8);
        for (int index = 0; index < 8; index++) coverage.resolve(index, State.REACHED);

        assertEquals(
                "walked 1 lap around the point: all 8 waypoints verified", OrbitReport.success("point", 1, coverage));
    }

    @Test
    void bypassesAreNamedSeparatelyFromDirectArrivals() {
        OrbitCoverage coverage = new OrbitCoverage(16);
        for (int index = 0; index < 14; index++) {
            coverage.resolve(index, index < 3 ? State.BYPASSED : State.REACHED);
        }
        coverage.resolve(14, State.BLOCKED);
        coverage.resolve(15, State.BLOCKED);

        assertEquals(
                "covered 14 of 16 waypoints of 2 laps around the point, 3 by a safe bypass; stopped: a wall covers the arc",
                OrbitReport.partial("point", 2, coverage, "a wall covers the arc"));
    }

    @Test
    void aBlockedFirstWaypointReportsNoCoverage() {
        OrbitCoverage coverage = new OrbitCoverage(8);
        coverage.resolve(0, State.BLOCKED);

        assertEquals(
                "covered 0 of 8 waypoints of 1 lap around the cow; stopped: something keeps blocking the way",
                OrbitReport.partial("cow", 1, coverage, "something keeps blocking the way"));
    }

    @Test
    void anObstructedLapIsNeverTheSuccessSentence() {
        OrbitCoverage coverage = new OrbitCoverage(8);
        coverage.resolve(0, State.BLOCKED);
        for (int index = 1; index < 8; index++) coverage.resolve(index, State.REACHED);

        assertEquals(
                "covered 7 of 8 waypoints of 1 lap around the point; stopped: something keeps blocking the way",
                OrbitReport.partial("point", 1, coverage, "something keeps blocking the way"));
    }
}
