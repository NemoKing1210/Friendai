package dev.friendai.crafting.obtain;

import java.util.Optional;

/**
 * An expanding square walk around a start point, one leg per step: east 16, south 16, west 32, north 32,
 * east 48, … The waypoints fill the surface around the companion as the legs grow, instead of retracing the
 * same four spokes.
 */
public final class Spiral {
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, -1, 0, 1};

    private final int legLength;
    private final int maxRadius;
    private final double originX;
    private final double originZ;
    private double x;
    private double z;
    private int leg;

    public Spiral(double originX, double originZ, int legLength, int maxRadius) {
        if (legLength <= 0) {
            throw new IllegalArgumentException("leg length must be positive: " + legLength);
        }
        if (maxRadius <= 0) {
            throw new IllegalArgumentException("radius must be positive: " + maxRadius);
        }
        this.originX = originX;
        this.originZ = originZ;
        this.x = originX;
        this.z = originZ;
        this.legLength = legLength;
        this.maxRadius = maxRadius;
    }

    /** The next waypoint, or empty when the next leg would leave the radius. */
    public Optional<double[]> next() {
        int length = legLength * (leg / 2 + 1);
        double nextX = x + DX[leg % 4] * length;
        double nextZ = z + DZ[leg % 4] * length;
        leg++;
        if (Math.hypot(nextX - originX, nextZ - originZ) > maxRadius) {
            return Optional.empty();
        }
        x = nextX;
        z = nextZ;
        return Optional.of(new double[] {x, z});
    }
}
