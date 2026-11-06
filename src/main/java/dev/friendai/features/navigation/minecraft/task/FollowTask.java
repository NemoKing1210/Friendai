package dev.friendai.features.navigation.minecraft.task;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.minecraft.nav.LevelCellView;
import dev.friendai.features.navigation.minecraft.nav.MoveHelper;
import dev.friendai.features.navigation.minecraft.nav.ResourceJourney;
import dev.friendai.features.navigation.pathing.GoalPoint;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.task.Task;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Stays close to the owner. Never finishes by itself. */
public final class FollowTask implements Task {
    /** The gap between where the companion stops and where it sets off again. */
    private static final double START_MARGIN = 2.0;

    private static final int RETRY_TICKS = 40;
    /** How long the terrain view under an airborne owner is reused. */
    private static final int TERRAIN_TICKS = 5;
    /**
     * How far below the owner's feet the follower's goal may sit. The ground under an airborne owner is already
     * resolved by {@link #followTarget}, so the only step left for the snap is a slab edge or a gap of a block; a
     * deeper one put the goal on the floor of whatever hole the owner stood over, and the follower then walked to the
     * rim, found no route down and waited there, outside its follow distance.
     */
    private static final int SNAP_DROP = 2;

    private final boolean quiet;
    private boolean moving;
    private MoveHelper mover;
    private double stopDistance = -1.0;
    private int retryCooldown;
    private LevelCellView terrain;
    private int terrainTick = Integer.MIN_VALUE;
    private static final double JOURNEY_DISTANCE = 64.0;
    private static final double JOURNEY_TARGET_MARGIN = 16.0;
    private ResourceJourney journey;
    private Pos journeyTarget;
    /** The last measured gap to the owner, for the debug view and a failing trace. */
    private double lastDistance = Double.NaN;

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
            mover = new MoveHelper(1.0, stopDistance, 4.0, SNAP_DROP);
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
        lastDistance = distance;
        if (moving && distance <= stopDistance) {
            moving = false;
            mover.stopAtDestination(c);
            return TaskStatus.RUNNING;
        }
        if (!moving && distance > stopDistance + START_MARGIN) {
            moving = true;
        }
        if (!moving) {
            if (journey != null) journey.stop(c);
            journey = null;
            journeyTarget = null;
            return TaskStatus.RUNNING;
        }
        if (distance > JOURNEY_DISTANCE) {
            if (journey == null || journeyTarget == null || journeyTargetDistance(target) > JOURNEY_TARGET_MARGIN) {
                if (journey != null) journey.stop(c);
                Pos destination =
                        new Pos((int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z));
                var settings = c.resourceSearchSettings();
                journey = new ResourceJourney(
                        c,
                        c.level().dimension().identifier().toString(),
                        destination,
                        null,
                        c.ownerKnowledge().trails(),
                        settings.allowExpeditions() && settings.chunkLoadingEnabled());
                journeyTarget = destination;
            }
            switch (journey.tick(c)) {
                case ARRIVED -> {
                    journey.stop(c);
                    journey = null;
                    journeyTarget = null;
                    moving = false;
                }
                case BLOCKED, CANCELLED -> {
                    journey.stop(c);
                    journey = null;
                    journeyTarget = null;
                    retryCooldown = RETRY_TICKS;
                }
                case WAITING_FOR_CHUNK, WORKING -> {
                    // The journey owns movement and the lease for this leg.
                }
            }
            return TaskStatus.RUNNING;
        }
        if (journey != null) {
            journey.stop(c);
            journey = null;
            journeyTarget = null;
        }
        mover.following(distance);
        switch (mover.tick(c, target)) {
            case ARRIVED -> {
                mover.stopAtDestination(c);
                moving = false;
            }
            case STUCK -> {
                mover.stop(c);
                moving = false;
                retryCooldown = RETRY_TICKS;
            }
            default -> {}
        }
        return TaskStatus.RUNNING;
    }

    private double journeyTargetDistance(Vec3 target) {
        if (journeyTarget == null) return Double.MAX_VALUE;
        double dx = target.x - journeyTarget.x();
        double dy = target.y - journeyTarget.y();
        double dz = target.z - journeyTarget.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The owner's feet when grounded; otherwise the highest standable cell beneath the owner. */
    private Vec3 followTarget(TaskContext c, ServerPlayer owner) {
        boolean passiveFlight = c.defaultActivity() && c.passiveFlightAllowed();
        if (c.flightAuthorized() || passiveFlight) {
            Vec3 away = c.companion().position().subtract(owner.position());
            away = new Vec3(away.x, 0.0, away.z);
            if (away.lengthSqr() < 1.0E-6) away = new Vec3(1.0, 0.0, 0.0);
            return owner.position().add(away.normalize().scale(1.5));
        }
        boolean grounded = owner.onGround()
                || owner.isInWater()
                || owner.onClimbable()
                || owner.getVehicle() != null && owner.getVehicle().onGround();
        if (grounded) {
            return owner.position();
        }
        LevelCellView view = terrain(c.companion());
        return GoalPoint.groundBelow(view, owner.getBlockX(), owner.getBlockY(), owner.getBlockZ(), 64)
                .map(pos -> new Vec3(pos.x() + 0.5, pos.y(), pos.z() + 0.5))
                .orElse(null);
    }

    /**
     * The terrain view for this follow tick. An airborne owner asks for the ground below every tick while nothing
     * moves, so the view is kept for a few ticks instead of rebuilding its cache each tick.
     */
    private LevelCellView terrain(CompanionEntity companion) {
        int now = companion.tickCount;
        if (terrain == null || now < terrainTick || now - terrainTick >= TERRAIN_TICKS) {
            terrain = LevelCellView.terrain(companion, 128);
            terrainTick = now;
        }
        return terrain;
    }

    @Override
    public void stop(TaskContext c) {
        if (journey != null) journey.stop(c);
        journey = null;
        journeyTarget = null;
        if (mover != null) {
            mover.stop(c);
        }
    }

    @Override
    public String describe() {
        return "following the owner";
    }

    @Override
    public String debugStatus() {
        String gap = Double.isNaN(lastDistance) ? "?" : Double.toString(Math.round(lastDistance * 100.0) / 100.0);
        return (moving ? "moving" : "waiting") + " at " + gap + "/" + stopDistance + ", retry " + retryCooldown + ", "
                + (mover == null ? "no mover" : mover.debugStatus());
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
        // An explicit order must not turn into the quiet default activity after a restart; a checkpoint written
        // before this flag existed carries "" and is read as explicit, which is what it was.
        return quiet ? "quiet" : "loud";
    }
}
