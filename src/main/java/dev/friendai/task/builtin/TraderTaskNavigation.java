package dev.friendai.task.builtin;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.pathing.GoalReach;
import dev.friendai.pathing.PathingConfig;
import dev.friendai.pathing.Pos;
import dev.friendai.task.TaskContext;
import dev.friendai.task.TaskSettings;
import dev.friendai.task.nav.Navigator;
import dev.friendai.trading.TraderRecord;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Shared exact-UUID checks and no-terrain-change route setup for the trader tasks. */
final class TraderTaskNavigation {
    static final int MAX_TASK_TICKS = 1_200;
    static final double MAX_RADIUS = 64.0;
    private static final double ACTION_REACH = 2.8;

    private TraderTaskNavigation() {}

    static String invalidContext(TaskContext context, UUID expectedOwner, String expectedDimension) {
        if (context == null || context.companion() == null || context.level() == null)
            return "task context is unavailable";
        if (!context.companion().isAlive()) return "companion is no longer alive";
        if (context.companion().level() != context.level()) return "companion changed level";
        if (expectedOwner == null || !expectedOwner.equals(context.companion().ownerUuid()))
            return "companion owner changed";
        if (!expectedDimension.equals(context.level().dimension().identifier().toString()))
            return "companion changed dimension";
        if (context.level().getServer().getPlayerList().getPlayer(expectedOwner) == null)
            return "owner is no longer online";
        return null;
    }

    static boolean inBoundedArea(CompanionEntity companion, Entity target) {
        return companion.distanceToSqr(target) <= MAX_RADIUS * MAX_RADIUS;
    }

    static BlockPos lastKnownPosition(TraderRecord record, String dimension) {
        if (record == null || dimension == null || !dimension.equals(record.dimension())) return null;
        return new BlockPos(record.x(), record.y(), record.z());
    }

    static boolean inBoundedArea(CompanionEntity companion, BlockPos hint) {
        return companion.distanceToSqr(hint.getX() + 0.5, hint.getY() + 0.5, hint.getZ() + 0.5)
                <= MAX_RADIUS * MAX_RADIUS;
    }

    static boolean inActionReach(CompanionEntity companion, Entity target) {
        var eye = companion.getEyePosition();
        var center = target.getBoundingBox().getCenter();
        if (eye.distanceTo(center) > ACTION_REACH) return false;
        return companion
                        .level()
                        .clip(new ClipContext(
                                eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion))
                        .getType()
                == HitResult.Type.MISS;
    }

    static boolean atHint(CompanionEntity companion, BlockPos hint) {
        var eye = companion.getEyePosition();
        var center = Vec3.atCenterOf(hint);
        if (eye.distanceTo(center) > ACTION_REACH) return false;
        return companion
                        .level()
                        .clip(new ClipContext(
                                eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion))
                        .getType()
                == HitResult.Type.MISS;
    }

    static Navigator navigator(CompanionEntity companion) {
        PathingConfig noTerrainChanges = TaskSettings.pathing.withActions(false, false);
        return new Navigator(companion, false, noTerrainChanges);
    }

    static void aimAt(Navigator navigator, Entity target, CompanionEntity companion) {
        aimAt(navigator, target.blockPosition(), companion);
    }

    static void aimAt(Navigator navigator, BlockPos pos, CompanionEntity companion) {
        navigator.setGoal(new GoalReach(
                List.of(new Pos(pos.getX(), pos.getY(), pos.getZ())), ACTION_REACH, companion.getEyeHeight()));
    }

    static void stop(Navigator navigator, TaskContext context) {
        if (navigator == null) return;
        if (context.level() instanceof ServerLevel) navigator.stop(context);
        else context.companion().getNavigation().stop();
    }
}
