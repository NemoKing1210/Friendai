package dev.friendai.combat;

/**
 * Where to step when a second threat is close enough to flank. Pure geometry: the adapter checks the ground before
 * the step, this class only decides the direction.
 */
public final class GroupPressure {
    private GroupPressure() {}

    public record Step(double dx, double dz) {}

    /**
     * A unit step perpendicular to the line to the current target, on the side that moves away from the second
     * threat. Keeping the target direction as the reference keeps the target inside melee reach while the step
     * breaks the second attacker's line.
     */
    public static Step sidestep(
            double selfX, double selfZ, double targetX, double targetZ, double otherX, double otherZ) {
        double dx = targetX - selfX;
        double dz = targetZ - selfZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-9) {
            return new Step(1.0, 0.0);
        }
        double ux = dx / length;
        double uz = dz / length;
        double px = -uz;
        double pz = ux;
        double otherSide = px * (otherX - selfX) + pz * (otherZ - selfZ);
        if (otherSide > 0.0) {
            px = -px;
            pz = -pz;
        }
        return new Step(px, pz);
    }
}
