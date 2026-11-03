package dev.friendai.features.mining.excavation;

import java.util.Locale;
import java.util.Optional;

/** The region a single excavation request describes. */
public enum ExcavationShape {
    BOX,
    TUNNEL,
    STAIR,
    SHAFT,
    STRIP;

    public static Optional<ExcavationShape> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
