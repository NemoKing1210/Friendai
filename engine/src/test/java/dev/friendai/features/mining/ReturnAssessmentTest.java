package dev.friendai.features.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.spatial.Node;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnAssessmentTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final Node ANCHOR = new Node(0, 64, 0);
    private static final Node STANCE = new Node(1, 62, 0);

    @Test
    void aVerifiedReversibleStairIsSafe() {
        var evidence = evidence(
                List.of(
                        new ReturnAssessment.Leg(STANCE, new Node(1, 63, 0), 0, true, true),
                        new ReturnAssessment.Leg(new Node(1, 63, 0), ANCHOR, 0, true, true)),
                true,
                true);

        var result = ReturnAssessment.assess(evidence, 0);

        assertEquals(ReturnAssessment.Status.SAFE, result.status());
        assertEquals(ANCHOR, result.anchor());
        assertEquals(0, result.requiredSupports());
    }

    @Test
    void aKnownRiseReportsTheSupportShortfall() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 2, true, true)), true, true);

        var result = ReturnAssessment.assess(evidence, 1);

        assertEquals(ReturnAssessment.Status.NEEDS_SUPPORTS, result.status());
        assertEquals(2, result.requiredSupports());
    }

    @Test
    void aBlockedOrChangedReturnRouteIsNotEvidence() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, false)), true, true);

        assertEquals(
                ReturnAssessment.Status.UNKNOWN_OR_BLOCKED,
                ReturnAssessment.assess(evidence, 8).status());
    }

    @Test
    void anUnloadedAnchorIsNotSafe() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, true)), false, true);

        assertEquals(
                ReturnAssessment.Status.UNKNOWN_OR_BLOCKED,
                ReturnAssessment.assess(evidence, 8).status());
    }

    @Test
    void staleEvidenceIsNotSafe() {
        var evidence = evidence(List.of(new ReturnAssessment.Leg(STANCE, ANCHOR, 0, true, true)), true, false);

        assertEquals(
                ReturnAssessment.Status.UNKNOWN_OR_BLOCKED,
                ReturnAssessment.assess(evidence, 8).status());
    }

    private static ReturnAssessment.RouteEvidence evidence(
            List<ReturnAssessment.Leg> legs, boolean anchorLoaded, boolean fresh) {
        return new ReturnAssessment.RouteEvidence(DIMENSION, DIMENSION, ANCHOR, STANCE, legs, anchorLoaded, fresh);
    }
}
