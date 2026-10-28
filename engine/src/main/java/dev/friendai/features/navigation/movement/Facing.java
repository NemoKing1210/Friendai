package dev.friendai.features.navigation.movement;

import java.util.Locale;
import java.util.Optional;

/**
 * Yaw and pitch arithmetic in the game's convention: yaw 0 faces south (+Z), 90 faces west (-X), 180 north (-Z) and
 * -90 east (+X); pitch -90 looks straight up and 90 straight down.
 */
public final class Facing {
    private Facing() {}

    /** A horizontal offset in blocks. */
    public record Offset(double dx, double dz) {}

    /** Wraps any angle into [-180, 180). */
    public static float normalize(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    /** The yaw that faces from the origin toward {@code (dx, dz)}; 0 when the offset is zero. */
    public static float yawToward(double dx, double dz) {
        if (dx == 0.0 && dz == 0.0) {
            return 0.0f;
        }
        return normalize((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** The pitch that looks from the origin toward {@code (dx, dy, dz)}. */
    public static float pitchToward(double dx, double dy, double dz) {
        double horizontal = Math.hypot(dx, dz);
        return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
    }

    /** Moves {@code current} toward {@code target} along the short way by at most {@code maxStep} degrees. */
    public static float stepToward(float current, float target, float maxStep) {
        float delta = normalize(target - current);
        if (Math.abs(delta) <= maxStep) {
            return normalize(target);
        }
        return normalize(current + Math.copySign(maxStep, delta));
    }

    /** The absolute angular distance between two yaws, 0 to 180. */
    public static float difference(float a, float b) {
        return Math.abs(normalize(a - b));
    }

    /** The unit offset one block ahead of the given yaw. */
    public static Offset forward(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Offset(-Math.sin(radians), Math.cos(radians));
    }

    /** The unit offset one block to the left of someone facing the given yaw. */
    public static Offset left(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Offset(Math.cos(radians), Math.sin(radians));
    }

    /**
     * Offset of {@code blocks} blocks in a direction relative to a yaw.
     *
     * @param direction forward, back, left or right
     */
    public static Optional<Offset> relative(float yaw, String direction, double blocks) {
        Offset forward = forward(yaw);
        Offset left = left(yaw);
        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "forward" -> Optional.of(new Offset(forward.dx() * blocks, forward.dz() * blocks));
            case "back", "backward" -> Optional.of(new Offset(-forward.dx() * blocks, -forward.dz() * blocks));
            case "left" -> Optional.of(new Offset(left.dx() * blocks, left.dz() * blocks));
            case "right" -> Optional.of(new Offset(-left.dx() * blocks, -left.dz() * blocks));
            default -> Optional.empty();
        };
    }

    /** The absolute yaw of a compass direction, or empty for anything else. */
    public static Optional<Float> compass(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "south" -> Optional.of(0.0f);
            case "west" -> Optional.of(90.0f);
            case "north" -> Optional.of(180.0f);
            case "east" -> Optional.of(-90.0f);
            default -> Optional.empty();
        };
    }

    /** The compass name closest to a yaw, for reports to the LLM. */
    public static String compassName(float yaw) {
        float wrapped = normalize(yaw);
        if (wrapped >= -45.0f && wrapped < 45.0f) {
            return "south";
        }
        if (wrapped >= 45.0f && wrapped < 135.0f) {
            return "west";
        }
        if (wrapped >= -135.0f && wrapped < -45.0f) {
            return "east";
        }
        return "north";
    }
}
