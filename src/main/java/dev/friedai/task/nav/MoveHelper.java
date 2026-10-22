package dev.friedai.task.nav;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

/** Walks a mob toward a point, recomputing the path periodically and noticing when it makes no progress. */
public final class MoveHelper {
    public enum State {
        MOVING,
        ARRIVED,
        STUCK
    }

    private static final int REPATH_TICKS = 10;
    private static final int STUCK_CHECK_TICKS = 100;
    private static final double MIN_PROGRESS = 0.5;

    private final double speed;
    private final double arriveDistance;
    private int ticks;
    private int sinceCheckpoint;
    private double checkpointDistance;

    public MoveHelper(double speed, double arriveDistance) {
        this.speed = speed;
        this.arriveDistance = arriveDistance;
    }

    public State tick(PathfinderMob mob, Vec3 target) {
        double dx = target.x - mob.getX();
        double dy = target.y - mob.getY();
        double dz = target.z - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= arriveDistance && Math.abs(dy) <= 3.0) {
            mob.getNavigation().stop();
            return State.ARRIVED;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (ticks % REPATH_TICKS == 0) {
            mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
        }
        ticks++;
        if (sinceCheckpoint == 0) {
            checkpointDistance = distance;
        }
        sinceCheckpoint++;
        if (sinceCheckpoint >= STUCK_CHECK_TICKS) {
            if (checkpointDistance - distance < MIN_PROGRESS) {
                return State.STUCK;
            }
            sinceCheckpoint = 0;
        }
        return State.MOVING;
    }

    public void reset() {
        ticks = 0;
        sinceCheckpoint = 0;
    }
}
