package dev.friendai.perception.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.spatial.Pos;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A verification settles each member on its own evidence, so a hidden or unsettled block is never forgotten. */
class ResourceCandidatesTest {
    private static final Pos KEPT = new Pos(1, 64, 1);
    private static final Pos GONE = new Pos(2, 64, 1);
    private static final Pos HIDDEN = new Pos(3, 64, 1);

    private static Map<Pos, ResourceCandidates.MemberResult> results(ResourceCandidates.MemberResult... values) {
        Map<Pos, ResourceCandidates.MemberResult> members = new LinkedHashMap<>();
        Pos[] positions = {GONE, KEPT, HIDDEN};
        for (int i = 0; i < values.length; i++) {
            members.put(positions[i], values[i]);
        }
        return members;
    }

    @Test
    void onlyACompletedRemovalForgetsAMember() {
        var report = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.PRESENT,
                        ResourceCandidates.MemberResult.OCCLUDED),
                false);

        assertEquals(java.util.Set.of(GONE), report.removed(), "a present or hidden member is never forgotten");
        assertTrue(report.blockedSightline(), "a blocked member marks the viewpoint so it is not retried at once");
        assertEquals(ResourceCandidates.Verification.PRESENT, report.verdict());
    }

    @Test
    void aBudgetThatRanOutLeavesEveryMemberUnsettled() {
        var report = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.PENDING,
                        ResourceCandidates.MemberResult.PENDING,
                        ResourceCandidates.MemberResult.PENDING),
                false);

        assertTrue(report.removed().isEmpty(), "an unfinished check proves nothing about a block");
        assertFalse(report.blockedSightline());
        assertEquals(ResourceCandidates.Verification.PENDING, report.verdict());
    }

    @Test
    void anUnloadedChunkIsNotEvidenceOfAnEmptySite() {
        var report = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.UNLOADED,
                        ResourceCandidates.MemberResult.UNLOADED,
                        ResourceCandidates.MemberResult.UNLOADED),
                false);

        assertTrue(report.removed().isEmpty());
        assertEquals(ResourceCandidates.Verification.UNLOADED, report.verdict());
    }

    @Test
    void everyCompletedAbsenceForgetsTheSite() {
        var report = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.REMOVED),
                false);

        assertEquals(java.util.Set.of(GONE, KEPT, HIDDEN), report.removed());
        assertFalse(report.blockedSightline());
        assertEquals(ResourceCandidates.Verification.ABSENT, report.verdict());
    }

    @Test
    void aBlockedMemberWaitsForTheChecksStillRunning() {
        var waiting = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.PENDING,
                        ResourceCandidates.MemberResult.OCCLUDED),
                false);
        assertEquals(
                ResourceCandidates.Verification.PENDING,
                waiting.verdict(),
                "a removal already proven must not be lost because a neighbour is blocked");
        assertEquals(java.util.Set.of(GONE), waiting.removed());

        var settled = ResourceCandidates.VerificationReport.of(
                results(
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.REMOVED,
                        ResourceCandidates.MemberResult.OCCLUDED),
                false);
        assertEquals(ResourceCandidates.Verification.OCCLUDED, settled.verdict());
        assertEquals(java.util.Set.of(GONE, KEPT), settled.removed(), "a blocked member is never forgotten");
    }

    @Test
    void aBlockedViewpointIsReportedWithoutMemberResults() {
        var report = ResourceCandidates.VerificationReport.blockedViewpoint();

        assertTrue(report.removed().isEmpty(), "a viewpoint nothing could be seen from must not forget a member");
        assertEquals(ResourceCandidates.Verification.OCCLUDED, report.verdict());
    }
}
