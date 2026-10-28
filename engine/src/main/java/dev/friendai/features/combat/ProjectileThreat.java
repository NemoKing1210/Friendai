package dev.friendai.features.combat;

/**
 * Bounded short-horizon projectile geometry. Positions and velocities are plain values from the server
 * snapshot; nothing here reads the world or runs a path search.
 */
public final class ProjectileThreat {
    private ProjectileThreat() {}

    /** Current projectile position and velocity, with its collision radius. */
    public record Facts(
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ,
            double radius,
            boolean frontal,
            String type) {
        public static Facts of(
                double x,
                double y,
                double z,
                double vx,
                double vy,
                double vz,
                double radius,
                boolean frontal,
                String type) {
            return new Facts(x, y, z, vx, vy, vz, Math.max(0.0, radius), frontal, type == null ? "" : type);
        }
    }

    /** An axis-aligned box around the companion, inflated by the projectile radius by the caller if desired. */
    public record EntityBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public static EntityBounds around(double x, double y, double z, double halfWidth, double height) {
            return new EntityBounds(x - halfWidth, y, z - halfWidth, x + halfWidth, y + height, z + halfWidth);
        }

        public EntityBounds inflate(double amount) {
            return new EntityBounds(
                    minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
        }
    }

    /**
     * Segment/box intersection over {@code horizonTicks}. A projectile moving away or expiring beyond the horizon
     * does not intersect, which keeps an arrow that passed by from triggering a dodge.
     */
    public static boolean intersects(Facts facts, EntityBounds bounds, int horizonTicks) {
        if (horizonTicks <= 0) {
            return false;
        }
        EntityBounds box = bounds.inflate(facts.radius());
        double endX = facts.x() + facts.velocityX() * horizonTicks;
        double endY = facts.y() + facts.velocityY() * horizonTicks;
        double endZ = facts.z() + facts.velocityZ() * horizonTicks;
        double tMin = 0.0;
        double tMax = 1.0;
        double[] start = {facts.x(), facts.y(), facts.z()};
        double[] delta = {endX - facts.x(), endY - facts.y(), endZ - facts.z()};
        double[] min = {box.minX(), box.minY(), box.minZ()};
        double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) < 1.0E-9) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double inv = 1.0 / delta[axis];
            double t1 = (min[axis] - start[axis]) * inv;
            double t2 = (max[axis] - start[axis]) * inv;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return false;
            }
        }
        return true;
    }

    /**
     * Deflection is limited to projectile types whose vanilla path was verified for this mob. Minecraft 26.3 exposes
     * no verified PathfinderMob deflection hook for ghast fireballs (see the design's scoped limitations), so the
     * set is empty: the controller must dodge or take cover instead of simulating a parry.
     */
    public static boolean deflectable(Facts facts) {
        return false;
    }

    /** A projectile counts as frontal when it approaches from the half-space the companion faces. */
    public static boolean isFrontal(
            double projectileX, double projectileZ, double companionX, double companionZ, double lookX, double lookZ) {
        double dx = projectileX - companionX;
        double dz = projectileZ - companionZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-6) {
            return true;
        }
        double lookLength = Math.sqrt(lookX * lookX + lookZ * lookZ);
        if (lookLength < 1.0E-6) {
            return false;
        }
        double dot = (dx / length) * (lookX / lookLength) + (dz / length) * (lookZ / lookLength);
        return dot >= 0.25;
    }
}
