package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AcquisitionAttemptsTest {
    @Test
    void aFirstFailureIsRememberedAndPenalised() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("tool_materials", "minecraft:deepslate", "no safe return");

        assertEquals(1, attempts.failures("minecraft:deepslate"));
        assertEquals(AcquisitionAttempts.PENALTY_PER_FAILURE, attempts.penalty("minecraft:deepslate"));
    }

    @Test
    void aRepeatedFailureRaisesThePenaltyWithoutRemovingTheRow() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("tool_materials", "minecraft:deepslate", "no safe return");
        attempts.recordFailure("tool_materials", "minecraft:deepslate", "still unreachable");

        assertEquals(2, attempts.failures("minecraft:deepslate"));
        assertEquals(2 * AcquisitionAttempts.PENALTY_PER_FAILURE, attempts.penalty("minecraft:deepslate"));
        assertEquals(1, attempts.entries().size());
        assertEquals("still unreachable", attempts.entries().getFirst().lastCause());
    }

    @Test
    void theLedgerStaysBoundedAndDropsTheOldestSource() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();
        for (int i = 0; i < AcquisitionAttempts.MAX_ENTRIES + 4; i++) {
            attempts.recordFailure("family", "minecraft:source_" + i, "failed");
        }

        assertEquals(AcquisitionAttempts.MAX_ENTRIES, attempts.entries().size());
        assertEquals(0, attempts.failures("minecraft:source_0"));
        assertEquals(1, attempts.failures("minecraft:source_" + (AcquisitionAttempts.MAX_ENTRIES + 3)));
    }

    @Test
    void anUntriedSourceHasNoPenalty() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        assertEquals(0, attempts.failures("minecraft:stone"));
        assertEquals(0, attempts.penalty("minecraft:stone"));
        assertTrue(attempts.sourceFailures().isEmpty());
    }

    @Test
    void aLongCauseIsShortenedAndSingleLined() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("family", "minecraft:stone", "first line\nsecond line " + "x".repeat(400));

        String cause = attempts.entries().getFirst().lastCause();
        assertTrue(cause.length() <= 120, "cause was not shortened: " + cause.length());
        assertFalse(cause.contains("\n"));
    }

    @Test
    void twoRoutesBlockedTheSameWayStopTheJob() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure(
                "tool_materials", "minecraft:stone", "dug 3 blocks down (why=return-route-obstructed-4,2,2->4,3,2)");
        assertEquals("", attempts.repeatedBlocker(), "one refused route is not yet a blocked way");

        attempts.recordFailure(
                "tool_materials",
                "minecraft:cobblestone",
                "dug 2 blocks down (why=return-route-obstructed-8,2,8->8,3,8)");

        assertEquals("return-route-obstructed", attempts.repeatedBlocker());
        assertEquals(2, attempts.repeatedBlockerAttempts());
        assertEquals(java.util.List.of("minecraft:stone", "minecraft:cobblestone"), attempts.repeatedBlockerSources());
    }

    @Test
    void aDifferentBlockerStartsTheEvidenceOver() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-unloaded-5,2,5->5,3,5)");

        assertEquals("", attempts.repeatedBlocker());
        assertEquals(1, attempts.repeatedBlockerAttempts());
    }

    @Test
    void anUntypedFailureNeitherCountsNorBreaksTheStreak() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        attempts.recordFailure("family", "minecraft:stone", "no stone within 12 blocks");
        attempts.recordFailure("family", "minecraft:cobblestone", "x (why=return-route-obstructed-2,2,3->2,3,3)");

        assertEquals("return-route-obstructed", attempts.repeatedBlocker());
        assertEquals(2, attempts.repeatedBlockerAttempts());
    }

    @Test
    void aCreditedStepMakesTheRouteEligibleAgain() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();
        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        assertEquals("return-route-obstructed", attempts.repeatedBlocker());

        attempts.clearBlockers();
        assertEquals("", attempts.repeatedBlocker());
        assertTrue(attempts.repeatedBlockerSources().isEmpty());

        attempts.recordFailure("family", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        assertEquals("", attempts.repeatedBlocker(), "the new attempt is the first of its streak");
    }

    @Test
    void anotherNeedDoesNotShareTheStreak() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();

        attempts.recordFailure("tool_materials", "minecraft:stone", "x (why=return-route-obstructed-1,2,3->1,3,3)");
        attempts.recordFailure("fuel", "minecraft:stone", "x (why=return-route-obstructed-2,2,3->2,3,3)");

        assertEquals("", attempts.repeatedBlocker());
    }

    @Test
    void theBlockerKeyDropsTheFailedLeg() {
        assertEquals(
                "return-route-obstructed",
                AcquisitionAttempts.blockerKey("(why=return-route-obstructed-1,2,3->1,3,3 x2 cand=0)"));
        assertEquals(
                "return-route-obstructed",
                AcquisitionAttempts.blockerKey("(why=return-route-obstructed--4,2,3->-4,3,3)"));
        assertEquals("return-needs-supports", AcquisitionAttempts.blockerKey("(why=return-needs-supports-2-spare-0)"));
        assertEquals(
                "return-route-anchor-unloaded", AcquisitionAttempts.blockerKey("why=return-route-anchor-unloaded"));
        assertEquals("", AcquisitionAttempts.blockerKey("no stone within 12 blocks"));
        assertEquals(
                "",
                AcquisitionAttempts.blockerKey("(why=unreachable x0 cand=8)"),
                "a transient search failure is not a blocked way");
        assertEquals("", AcquisitionAttempts.blockerKey(null));
    }

    @Test
    void aBlankSourceIsIgnoredAndClearForgetsEverything() {
        AcquisitionAttempts attempts = new AcquisitionAttempts();
        attempts.recordFailure("family", "  ", "failed");
        attempts.recordFailure("family", null, "failed");
        assertTrue(attempts.entries().isEmpty());

        attempts.recordFailure("family", "minecraft:stone", "failed (why=return-route-obstructed-1,2,3->1,3,3)");
        attempts.clear();

        assertTrue(attempts.entries().isEmpty());
        assertTrue(attempts.sourceFailures().isEmpty());
        assertEquals("", attempts.repeatedBlocker(), "a cleared ledger carries no blocker");
    }
}
