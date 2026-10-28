package dev.friendai.perception.resource;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.Objects;

/** Short-lived evidence of an open cave passage proven by one direct, unobstructed sight ray. */
public record CaveEntranceObservation(String dimension, Pos approach, Pos opening, Pos interior, long observedTick) {
    public CaveEntranceObservation {
        if (dimension == null || dimension.isBlank() || observedTick < 0)
            throw new IllegalArgumentException("invalid cave entrance observation");
        Objects.requireNonNull(approach);
        Objects.requireNonNull(opening);
        Objects.requireNonNull(interior);
    }
}
