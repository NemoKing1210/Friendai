package dev.friendai.features.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThreatTableTest {

    private static ThreatTable.Candidate farAttacker() {
        return new ThreatTable.Candidate(6.0, false, true, true, 6.0);
    }

    @Test
    void theNearReachingMobBeatsTheFarCurrentAttacker() {
        List<ThreatTable.Candidate> candidates =
                List.of(farAttacker(), new ThreatTable.Candidate(2.0, true, false, false, 2.0));
        assertEquals(1, ThreatTable.best(candidates, 24));
    }

    @Test
    void hittingUsOutranksAnIdleMobAtTheSameDistance() {
        List<ThreatTable.Candidate> candidates = List.of(
                new ThreatTable.Candidate(4.0, true, false, false, 4.0),
                new ThreatTable.Candidate(4.0, true, true, false, 4.0));
        assertEquals(1, ThreatTable.best(candidates, 24));
    }

    @Test
    void beyondThePursuitBoundLosesToAnyInBoundCandidate() {
        List<ThreatTable.Candidate> candidates = List.of(
                new ThreatTable.Candidate(1.0, true, true, true, 30.0),
                new ThreatTable.Candidate(12.0, false, false, false, 12.0));
        assertEquals(1, ThreatTable.best(candidates, 24));
    }

    @Test
    void tiesKeepTheEarlierCandidate() {
        ThreatTable.Candidate same = new ThreatTable.Candidate(3.0, false, false, false, 3.0);
        assertEquals(0, ThreatTable.best(List.of(same, same), 24));
    }

    @Test
    void emptyListHasNoTarget() {
        assertEquals(-1, ThreatTable.best(List.of(), 24));
    }
}
