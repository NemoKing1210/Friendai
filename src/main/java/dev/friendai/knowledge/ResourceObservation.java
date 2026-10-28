package dev.friendai.knowledge;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.Objects;

public record ResourceObservation(String blockId, Location location, Pos viewpoint, long observedTick) {
    public ResourceObservation {
        requireText(blockId, "blockId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(viewpoint, "viewpoint");
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
