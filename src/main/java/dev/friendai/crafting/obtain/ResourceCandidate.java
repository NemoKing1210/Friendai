package dev.friendai.crafting.obtain;

import dev.friendai.pathing.Pos;
import java.util.Objects;

/** A historical resource site offered as a conditional acquisition source. */
public record ResourceCandidate(long siteId, String blockId, String dimension, Pos position, Pos approach,
        int lastObservedBlocks, long ageTicks, boolean visibleNow, boolean approximate, double estimatedCost) {
    public ResourceCandidate {
        if (siteId < 1 || blockId == null || blockId.isBlank() || dimension == null || dimension.isBlank()
                || lastObservedBlocks < 1 || ageTicks < 0 || !Double.isFinite(estimatedCost) || estimatedCost < 0) {
            throw new IllegalArgumentException("invalid resource candidate");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(approach, "approach");
    }
}
