package dev.friendai.task.nav;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What the companion can reach without a solid block in the way: the first <em>colliding</em> block its eye ray meets,
 * so leaves or a wall hide a target but a vine, tall grass or an open door does not. A player's crosshair also catches
 * a vine's thin outline; the companion acts with its hands instead, and a block with no collision shape cannot stop
 * them, so it must not cause a pointless detour around or dig through a jungle vine.
 */
public final class Sight {
    /** How far around a block the companion looks for a free cell with a view of it. */
    private static final int VIEW_RANGE = 3;
    private static final double EPSILON = 1.0E-4;
    /** How far from the centre toward a face the extra aim points lie; just inside the block. */
    private static final double FACE_AIM = 0.45;

    private Sight() {}

    /** Whether the viewer's eyes see pos: the first block their ray meets is pos itself. */
    public static boolean canSee(ServerLevel level, Entity viewer, BlockPos pos) {
        return canSeeFrom(level, viewer, viewer.getEyePosition(), pos);
    }

    /**
     * The first block the viewer's ray meets on its way to {@code pos}, or null when {@code pos} itself is what the
     * ray meets first. This is what a player would have to break before they could see {@code pos}.
     */
    public static BlockPos firstBlockOnRay(ServerLevel level, Entity viewer, BlockPos pos) {
        BlockHitResult hit = raycast(level, viewer, viewer.getEyePosition(), Vec3.atCenterOf(pos));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos)) {
            return null;
        }
        // A target without a collision shape (a vine, tall grass) cannot itself stop the ray; a collider met after
        // its centre lies behind it and is not an obstruction either.
        if (isColliding(level, pos)) {
            return hit.getBlockPos();
        }
        double targetDistance = viewer.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
        return hit.getLocation().distanceToSqr(viewer.getEyePosition()) <= targetDistance + EPSILON
                ? hit.getBlockPos() : null;
    }

    /** Whether a ray from {@code eye} meets pos before any other block. */
    public static boolean canSeeFrom(ServerLevel level, Entity viewer, Vec3 eye, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        if (meets(level, viewer, eye, center, pos)) {
            return true;
        }
        // A player may aim at any part of the block: a block beside the feet with stone above it hides its centre
        // behind that stone, but its side face is in plain view.
        for (Direction side : Direction.values()) {
            Vec3 face = center.add(side.getStepX() * FACE_AIM, side.getStepY() * FACE_AIM, side.getStepZ() * FACE_AIM);
            if (meets(level, viewer, eye, face, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean meets(ServerLevel level, Entity viewer, Vec3 eye, Vec3 aim, BlockPos pos) {
        BlockHitResult hit = raycast(level, viewer, eye, aim);
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
            return true;
        }
        // The target may have no collision shape (a vine, tall grass): then any collider met after the aim lies
        // behind it and does not hide it. A target with collision of its own must be the block the ray meets.
        return !isColliding(level, pos)
                && (hit.getType() != HitResult.Type.BLOCK
                        || hit.getLocation().distanceToSqr(eye) >= aim.distanceToSqr(eye) - EPSILON);
    }

    private static boolean isColliding(ServerLevel level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * The nearest free standing cell within reach of pos from which pos is visible and that the companion can reach
     * in the open from where it stands, or null when no such cell exists. Moving there is a step around a bush, not a
     * jump through a wall.
     */
    public static BlockPos viewSpot(ServerLevel level, CompanionEntity companion, BlockPos pos) {
        Vec3 target = Vec3.atCenterOf(pos);
        Vec3 eye = companion.getEyePosition();
        double eyeHeight = companion.getEyeHeight();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -VIEW_RANGE; dx <= VIEW_RANGE; dx++) {
                for (int dz = -VIEW_RANGE; dz <= VIEW_RANGE; dz++) {
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (candidate.equals(pos) || !freeStanding(level, candidate)) {
                        continue;
                    }
                    Vec3 candidateEye = new Vec3(
                            candidate.getX() + 0.5, candidate.getY() + eyeHeight, candidate.getZ() + 0.5);
                    double distance = candidateEye.distanceToSqr(eye);
                    if (distance >= bestDistance || candidateEye.distanceTo(target) > Approach.REACH) {
                        continue;
                    }
                    if (!clearLine(level, companion, eye, candidateEye)
                            || !canSeeFrom(level, companion, candidateEye, pos)) {
                        continue;
                    }
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static BlockHitResult raycast(ServerLevel level, Entity viewer, Vec3 from, Vec3 to) {
        // A block without a collision shape (a vine, tall grass, an open door) cannot stop a body reaching through
        // it, even though it may have a selectable outline a player's crosshair would catch.
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
    }

    /** Whether the ray from the eyes reaches {@code point} before any block does. */
    private static boolean clearLine(ServerLevel level, Entity viewer, Vec3 eye, Vec3 point) {
        BlockHitResult hit = raycast(level, viewer, eye, point);
        return hit.getType() == HitResult.Type.MISS
                || hit.getLocation().distanceToSqr(eye) >= point.distanceToSqr(eye) - EPSILON;
    }

    private static boolean freeStanding(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }
}
