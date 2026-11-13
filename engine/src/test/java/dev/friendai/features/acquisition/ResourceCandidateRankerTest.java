package dev.friendai.features.acquisition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.friendai.foundation.spatial.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResourceCandidateRankerTest {
    @Test
    void verifiedApproachAndReadyToolBeatCheaperUnknownSite() {
        ResourceCandidate verified = candidate(
                42, ResourceCandidate.ToolReadiness.READY, ResourceCandidate.ApproachEvidence.VERIFIED, 120, 100, 80);
        ResourceCandidate cheaper = candidate(
                41, ResourceCandidate.ToolReadiness.MISSING, ResourceCandidate.ApproachEvidence.UNKNOWN, 8, 1, 1);

        assertEquals(
                List.of(verified, cheaper),
                List.of(cheaper, verified).stream()
                        .sorted(ResourceCandidateRanker.comparator())
                        .toList());
    }

    @Test
    void returnCostBreaksTiesBeforeStableSiteIdentity() {
        ResourceCandidate longReturn = candidate(
                41, ResourceCandidate.ToolReadiness.READY, ResourceCandidate.ApproachEvidence.ESTIMATED, 6, 10, 90);
        ResourceCandidate shortReturn = candidate(
                42, ResourceCandidate.ToolReadiness.READY, ResourceCandidate.ApproachEvidence.ESTIMATED, 6, 50, 5);

        assertEquals(
                List.of(shortReturn, longReturn),
                List.of(longReturn, shortReturn).stream()
                        .sorted(ResourceCandidateRanker.comparator())
                        .toList());
    }

    private static ResourceCandidate candidate(
            long siteId,
            ResourceCandidate.ToolReadiness tool,
            ResourceCandidate.ApproachEvidence evidence,
            int count,
            double approachCost,
            double returnCost) {
        return new ResourceCandidate(
                siteId,
                "minecraft:stone",
                "minecraft:overworld",
                new Pos((int) siteId, 64, 0),
                new Pos((int) siteId, 64, 1),
                count,
                10,
                false,
                false,
                approachCost,
                tool,
                evidence,
                returnCost);
    }
}
