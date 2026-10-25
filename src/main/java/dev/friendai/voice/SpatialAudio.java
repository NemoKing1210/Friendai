package dev.friendai.voice;

/**
 * Turns listener/source geometry into per-ear gains. Yaw follows Minecraft conventions (0 = south,
 * 90 = west); front and back deliberately sound the same, so only the side and the distance matter.
 */
public final class SpatialAudio {
    private static final double SQRT_HALF = Math.sqrt(0.5);
    private static final double EPSILON = 1e-9;

    /** Both channels at centre and full distance level. */
    public static final Mix CENTER = new Mix(SQRT_HALF, SQRT_HALF, 1.0, 0.0);

    private SpatialAudio() {}

    /** One listener-relative result: channel gains, the attenuation on its own, and the distance. */
    public record Mix(double left, double right, double level, double distance) {}

    public static Mix mix(
            double listenerX,
            double listenerY,
            double listenerZ,
            double yawDegrees,
            double sourceX,
            double sourceY,
            double sourceZ,
            double range) {
        double dx = sourceX - listenerX;
        double dy = sourceY - listenerY;
        double dz = sourceZ - listenerZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double level = range <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, 1.0 - distance / range));
        if (level <= 0) {
            return new Mix(0.0, 0.0, 0.0, distance);
        }
        double horizontal = Math.hypot(dx, dz);
        double pan = 0.0;
        if (horizontal > EPSILON) {
            double yaw = Math.toRadians(yawDegrees);
            double rightX = -Math.cos(yaw);
            double rightZ = -Math.sin(yaw);
            pan = Math.max(-1.0, Math.min(1.0, (dx * rightX + dz * rightZ) / horizontal));
        }
        double angle = (pan + 1.0) * Math.PI / 4.0;
        return new Mix(Math.cos(angle) * level, Math.sin(angle) * level, level, distance);
    }
}
