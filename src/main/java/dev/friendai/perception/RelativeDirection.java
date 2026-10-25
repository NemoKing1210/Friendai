package dev.friendai.perception;

/** Compass and relative direction names for horizontal vectors. Pure text, no Minecraft types. */
public final class RelativeDirection {
    private static final String[] COMPASS = {
        "south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"
    };
    private static final String[] RELATIVE = {
        "ahead", "ahead-right", "right", "behind-right", "behind", "behind-left", "left", "ahead-left"
    };

    private RelativeDirection() {}

    /** Compass side of the vector (east is +x, south is +z). */
    public static String compass(double dx, double dz) {
        double bearing = normalize360(bearing(dx, dz));
        return COMPASS[Math.floorMod((int) Math.floor((bearing + 22.5) / 45.0), 8)];
    }

    /** Where the vector lies relative to someone facing {@code yawDegrees} (Minecraft yaw: 0 = south, -90 = east). */
    public static String relativeTo(double yawDegrees, double dx, double dz) {
        double relative = normalize180(bearing(dx, dz) - yawDegrees);
        return RELATIVE[Math.floorMod((int) Math.floor((relative + 22.5) / 45.0), 8)];
    }

    /** Degrees clockwise from south: 0 = south, 90 = west, +/-180 = north, -90 = east. */
    private static double bearing(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    private static double normalize360(double degrees) {
        double value = degrees % 360.0;
        return value < 0 ? value + 360.0 : value;
    }

    private static double normalize180(double degrees) {
        double value = normalize360(degrees);
        return value >= 180.0 ? value - 360.0 : value;
    }
}
