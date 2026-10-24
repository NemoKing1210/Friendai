package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.CostModel;
import dev.friendai.pathing.GoalPoint;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a mob toward a point, recomputing the path periodically and noticing when it makes no progress.
 *
 * <p>For a companion the walking is done by its {@link Navigator}, which can dig, pillar and bridge on the way; every
 * other mob keeps vanilla navigation.
 */
public final class MoveHelper {
    public enum State {
        MOVING,
        ARRIVED,
        STUCK
    }

    private static final int REPATH_TICKS = 10;
    private static final int STUCK_CHECK_TICKS = 100;
    private static final double MIN_PROGRESS = 0.5;
    /** How far the target may move before the search is restarted. */
    private static final double GOAL_TOLERANCE = 1.0;
    private static final double VERTICAL = 3.0;
    private static final int SNAP_DROP = 16;

    private final double speed;
    private final double arriveDistance;
    private int ticks;
    private int sinceCheckpoint;
    private double checkpointDistance;
    private Navigator navigator;
    private Vec3 lastGoal;

    public MoveHelper(double speed, double arriveDistance) {
        this.speed = speed;
        this.arriveDistance = arriveDistance;
    }

    public State tick(PathfinderMob mob, Vec3 target) {
        double dx = target.x - mob.getX();
        double dy = target.y - mob.getY();
        double dz = target.z - mob.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= arriveDistance && Math.abs(dy) <= VERTICAL) {
            mob.getNavigation().stop();
            return State.ARRIVED;
        }
        if (mob instanceof CompanionEntity companion) {
            return tickCompanion(companion, target);
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

    private State tickCompanion(CompanionEntity companion, Vec3 target) {
        companion.pace().update(false, Double.NaN);
        if (navigator == null) {
            navigator = new Navigator(companion);
        }
        if (lastGoal == null || lastGoal.distanceToSqr(target) > GOAL_TOLERANCE * GOAL_TOLERANCE) {
            lastGoal = target;
            navigator.setGoal(goalFor(companion, target));
        }
        TaskContext context = new TaskContext(companion, (ServerLevel) companion.level());
        return switch (navigator.tick(context)) {
            case ARRIVED -> State.ARRIVED;
            case UNREACHABLE -> State.STUCK;
            default -> State.MOVING;
        };
    }

    private GoalPoint goalFor(CompanionEntity companion, Vec3 target) {
        LevelCellView view = new LevelCellView(companion, TaskSettings.pathing, CostModel.DEFAULTS);
        Pos stand = GoalPoint.snapDown(
                view,
                (int) Math.floor(target.x),
                (int) Math.floor(target.y),
                (int) Math.floor(target.z),
                SNAP_DROP);
        return new GoalPoint(
                stand.x() + 0.5, stand.y(), stand.z() + 0.5, arriveDistance, VERTICAL, CostModel.DEFAULTS.walk());
    }

    public void reset() {
        ticks = 0;
        sinceCheckpoint = 0;
        navigator = null;
        lastGoal = null;
    }
}
