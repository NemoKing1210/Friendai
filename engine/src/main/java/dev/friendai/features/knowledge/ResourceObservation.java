package dev.friendai.features.knowledge;

import dev.friendai.foundation.spatial.Pos;
import java.util.Objects;

public record ResourceObservation(
        String blockId, Location location, Pos viewpoint, long observedTick, EvidenceSource source) {
    public enum EvidenceSource {
        DIRECT_SIGHT,
        ADJACENT_BREAK
    }

    public ResourceObservation(String blockId, Location location, Pos viewpoint, long observedTick) {
        this(blockId, location, viewpoint, observedTick, EvidenceSource.DIRECT_SIGHT);
    }

    public ResourceObservation {
        requireText(blockId, "blockId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(viewpoint, "viewpoint");
        Objects.requireNonNull(source, "source");
    }

    public record Location(String dimension, Pos pos) {
        public Location {
            requireText(dimension, "dimension");
            Objects.requireNonNull(pos, "pos");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
