package dev.friendai.features.acquisition;

import java.util.Comparator;
import java.util.Objects;

/** Stable, cheap ordering for remembered resource sites before a visit task revalidates them. */
public final class ResourceCandidateRanker {
    private ResourceCandidateRanker() {}

    public static Comparator<ResourceCandidate> comparator() {
        return ResourceCandidateRanker::compare;
    }

    public static int compare(ResourceCandidate left, ResourceCandidate right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        int evidence = compareApproachEvidence(left.approachEvidence(), right.approachEvidence());
        if (evidence != 0) return evidence;
        int supply = Integer.compare(right.lastObservedBlocks(), left.lastObservedBlocks());
        if (supply != 0) return supply;
        int tool = compareToolReadiness(left.toolReadiness(), right.toolReadiness());
        if (tool != 0) return tool;
        int cost = Double.compare(left.estimatedTripCost(), right.estimatedTripCost());
        if (cost != 0) return cost;
        return compareIdentity(left, right);
    }

    public static int compareApproachEvidence(
            ResourceCandidate.ApproachEvidence left, ResourceCandidate.ApproachEvidence right) {
        return Integer.compare(evidenceRank(left), evidenceRank(right));
    }

    public static int compareToolReadiness(
            ResourceCandidate.ToolReadiness left, ResourceCandidate.ToolReadiness right) {
        return Integer.compare(toolRank(left), toolRank(right));
    }

    public static int compareIdentity(ResourceCandidate left, ResourceCandidate right) {
        int site = Long.compare(left.siteId(), right.siteId());
        return site != 0 ? site : left.blockId().compareTo(right.blockId());
    }

    private static int evidenceRank(ResourceCandidate.ApproachEvidence evidence) {
        return switch (evidence) {
            case VERIFIED -> 0;
            case ESTIMATED -> 1;
            case UNKNOWN -> 2;
        };
    }

    private static int toolRank(ResourceCandidate.ToolReadiness readiness) {
        return switch (readiness) {
            case READY -> 0;
            case UNKNOWN -> 1;
            case MISSING -> 2;
        };
    }
}
