package dev.friendai.features.acquisition;

import dev.friendai.foundation.spatial.Pos;
import java.util.Objects;

/** A historical resource site offered as a conditional acquisition source. */
public record ResourceCandidate(
        long siteId,
        String blockId,
        String dimension,
        Pos position,
        Pos approach,
        int lastObservedBlocks,
        long ageTicks,
        boolean visibleNow,
        boolean approximate,
        double estimatedCost,
        ToolReadiness toolReadiness,
        ApproachEvidence approachEvidence,
        double estimatedReturnCost) {
    public enum ToolReadiness {
        READY,
        UNKNOWN,
        MISSING
    }

    public enum ApproachEvidence {
        VERIFIED,
        ESTIMATED,
        UNKNOWN
    }

    /** Compatibility constructor for candidates created before runtime evidence was available. */
    public ResourceCandidate(
            long siteId,
            String blockId,
            String dimension,
            Pos position,
            Pos approach,
            int lastObservedBlocks,
            long ageTicks,
            boolean visibleNow,
            boolean approximate,
            double estimatedCost) {
        this(
                siteId,
                blockId,
                dimension,
                position,
                approach,
                lastObservedBlocks,
                ageTicks,
                visibleNow,
                approximate,
                estimatedCost,
                ToolReadiness.UNKNOWN,
                ApproachEvidence.UNKNOWN,
                0);
    }

    public ResourceCandidate {
        if (siteId < 1
                || blockId == null
                || blockId.isBlank()
                || dimension == null
                || dimension.isBlank()
                || lastObservedBlocks < 1
                || ageTicks < 0
                || !Double.isFinite(estimatedCost)
                || estimatedCost < 0
                || !Double.isFinite(estimatedReturnCost)
                || estimatedReturnCost < 0) {
            throw new IllegalArgumentException("invalid resource candidate");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(approach, "approach");
        Objects.requireNonNull(toolReadiness, "toolReadiness");
        Objects.requireNonNull(approachEvidence, "approachEvidence");
    }

    public double estimatedTripCost() {
        return estimatedCost + estimatedReturnCost;
    }
}
