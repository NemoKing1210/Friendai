package dev.friendai.task.builtin;

import dev.friendai.pathing.GoalPoint;
import dev.friendai.task.Task;
import dev.friendai.task.nav.LevelCellView;
import dev.friendai.task.nav.MoveHelper;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import dev.friendai.creative.CreativeAction;
import dev.friendai.core.CreativeModeService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Stays close to the owner. Never finishes by itself. */
public final class FollowTask implements Task {
    /** The gap between where the companion stops and where it sets off again. */
    private static final double START_MARGIN = 2.0;
    private static final int RETRY_TICKS = 40;

    private final boolean quiet;
    private boolean moving;
    private MoveHelper mover;
    private double stopDistance = -1.0;
    private int retryCooldown;

    /** @param quiet true when started as the default activity, so the brain hears nothing about it */
    public FollowTask(boolean quiet) {
        this.quiet = quiet;
    }

    @Override
    public String type() {
        return "follow";
    }

    @Override
    public void start(TaskContext c) {}

    @Override
    public TaskStatus tick(TaskContext c) {
        double wanted = c.companion().body().followDistance();
        if (mover == null || wanted != stopDistance) {
            if (mover != null) {
                mover.stop(c);
            }
            stopDistance = wanted;
            mover = new MoveHelper(1.0, stopDistance, 4.0, 64);
            moving = false;
        }
        ServerPlayer owner = c.owner().orElse(null);
        if (owner == null || owner.level() != c.level()) {
            if (moving) {
                mover.stop(c);
            }
            moving = false;
            return TaskStatus.RUNNING;
        }
        if (retryCooldown > 0) {
            retryCooldown--;
            return TaskStatus.RUNNING;
        }
        Vec3 target = followTarget(c, owner);
        if (target == null) {
            mover.stop(c);
            moving = false;
            return TaskStatus.RUNNING;
        }
        double distance = c.companion().position().distanceTo(target);
        if (moving && distance <= stopDistance) {
            moving = false;
            mover.stop(c);
            return TaskStatus.RUNNING;
        }
        if (!moving && distance > stopDistance + START_MARGIN) {
            moving = true;
        }
        if (!moving) {
            return TaskStatus.RUNNING;
        }
        mover.following(distance);
        switch (mover.tick(c.companion(), target)) {
            case ARRIVED -> {
                mover.stop(c);
                moving = false;
            }
            case STUCK -> {
                mover.stop(c);
                moving = false;
                retryCooldown = RETRY_TICKS;
            }
            default -> { }
        }
        return TaskStatus.RUNNING;
    }

    /** The owner's feet when grounded; otherwise the highest standable cell beneath the owner. */
    private static Vec3 followTarget(TaskContext c, ServerPlayer owner) {
        boolean passiveFlight = c.runner() != null && c.runner().currentIsDefaultActivity()
                && CreativeModeService.ownerMayUsePassiveFlight(c.companion());
        if (c.canUseCreativeAction(CreativeAction.FLIGHT) || passiveFlight) {
            Vec3 away = c.companion().position().subtract(owner.position());
            away = new Vec3(away.x, 0.0, away.z);
            if (away.lengthSqr() < 1.0E-6) away = new Vec3(1.0, 0.0, 0.0);
            return owner.position().add(away.normalize().scale(1.5));
        }
        boolean grounded = owner.onGround() || owner.isInWater() || owner.onClimbable()
                || owner.getVehicle() != null && owner.getVehicle().onGround();
        if (grounded) {
            return owner.position();
        }
        LevelCellView view = LevelCellView.terrain(c.companion());
        return GoalPoint.groundBelow(view, owner.getBlockX(), owner.getBlockY(), owner.getBlockZ(), 64)
                .map(pos -> new Vec3(pos.x() + 0.5, pos.y(), pos.z() + 0.5))
                .orElse(null);
    }

    @Override
    public void stop(TaskContext c) {
        if (mover != null) {
            mover.stop(c);
        }
    }

    @Override
    public String describe() {
        return "following the owner";
    }

    @Override
    public String resultSummary() {
        return "stopped following";
    }

    @Override
    public boolean emitsEvents() {
        return !quiet;
    }

    @Override
    public boolean hasTimeout() {
        return false;
    }

    @Override
    public String saveData() {
        return "follow";
    }
}
