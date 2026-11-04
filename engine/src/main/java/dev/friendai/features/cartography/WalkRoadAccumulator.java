package dev.friendai.features.cartography;

import java.util.Objects;
import java.util.Optional;

/**
 * Turns a walked position stream into route segments. The comparison point is the last <em>recorded</em> route end,
 * not the previous sample, so a walk sampled every few blocks still accumulates: many short steps add up to a segment
 * once their combined displacement reaches the minimum. A dimension change starts a new stream without a segment.
 */
public final class WalkRoadAccumulator {
    /** One recorded route; the distance between its ends is at least the configured minimum. */
    public record Segment(String dimension, int fromX, int fromZ, int toX, int toZ) {}

    private final int minDistance;
    private String dimension = "";
    private int x;
    private int z;
    private int fromX;
    private int fromZ;
    private boolean hasPoint;

    /** @param minDistance how far the walk must move from the last recorded end before a segment is recorded */
    public WalkRoadAccumulator(int minDistance) {
        this.minDistance = Math.max(1, minDistance);
    }

    public void observe(String dimension, int x, int z) {
        this.x = x;
        this.z = z;
        if (!hasPoint || !Objects.equals(this.dimension, dimension)) {
            this.dimension = dimension == null ? "" : dimension;
            this.fromX = x;
            this.fromZ = z;
            this.hasPoint = true;
        }
    }

    /** The segment from the last recorded end, when the walk has moved far enough; an empty optional otherwise. */
    public Optional<Segment> take() {
        if (!hasPoint) {
            return Optional.empty();
        }
        long dx = (long) x - fromX;
        long dz = (long) z - fromZ;
        if (dx * dx + dz * dz < (long) minDistance * minDistance) {
            return Optional.empty();
        }
        Segment segment = new Segment(dimension, fromX, fromZ, x, z);
        fromX = x;
        fromZ = z;
        return Optional.of(segment);
    }
}
