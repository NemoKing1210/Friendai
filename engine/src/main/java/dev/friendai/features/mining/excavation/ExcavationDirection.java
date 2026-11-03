package dev.friendai.features.mining.excavation;

import java.util.Locale;
import java.util.Optional;

/** A pure cardinal/vertical direction; the minecraft adapter maps it to the game enum. */
public enum ExcavationDirection {
    NORTH(0, 0, -1),
    SOUTH(0, 0, 1),
    EAST(1, 0, 0),
    WEST(-1, 0, 0),
    DOWN(0, -1, 0),
    UP(0, 1, 0);

    private final int dx;
    private final int dy;
    private final int dz;

    ExcavationDirection(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }

    public int dz() {
        return dz;
    }

    public boolean horizontal() {
        return this != DOWN && this != UP;
    }

    /** The direction ninety degrees to the left, used by alternating strip branches. */
    public ExcavationDirection left() {
        return switch (this) {
            case NORTH -> WEST;
            case WEST -> SOUTH;
            case SOUTH -> EAST;
            case EAST -> NORTH;
            default -> this;
        };
    }

    /** The direction ninety degrees to the right, used by alternating strip branches. */
    public ExcavationDirection right() {
        return switch (this) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> this;
        };
    }

    public static Optional<ExcavationDirection> parse(String raw) {
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
