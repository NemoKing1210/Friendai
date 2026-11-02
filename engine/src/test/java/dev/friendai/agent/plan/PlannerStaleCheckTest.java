package dev.friendai.agent.plan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlannerStaleCheckTest {
    @Test
    void aChangedPlanIsStaleButALegacyProposalIsNot() {
        assertTrue(PlannerStaleCheck.stale("abc", "def"));
        assertFalse(PlannerStaleCheck.stale("abc", "abc"));
        assertFalse(PlannerStaleCheck.stale("", "def"), "no expectation means an older caller");
        assertFalse(PlannerStaleCheck.stale(null, "def"));
    }
}
