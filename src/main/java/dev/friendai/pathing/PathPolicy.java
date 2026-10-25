package dev.friendai.pathing;

import java.util.Optional;

/** A search-scoped hook for rejecting or preparing movement edges before they are relaxed. */
@FunctionalInterface
public interface PathPolicy {

    /** Leaves every edge unchanged. */
    PathPolicy DEFAULT = (view, step) -> Optional.of(step);

    /**
     * Prepares an edge for this search, or rejects it. A prepared step may add required breaks and their cost, but
     * must retain the original endpoints and movement semantics.
     */
    Optional<PathStep> prepare(CellView view, PathStep step);
}
