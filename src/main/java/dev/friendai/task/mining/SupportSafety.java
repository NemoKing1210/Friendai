package dev.friendai.task.mining;

import dev.friendai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The two questions behind {@link MiningRefusal.OwnSupport}: does the companion stand on this block, and would
 * breaking it drop the companion somewhere it can land? Both are read from the live world, never cached, and both
 * are cheap enough to run only when the target is close to the body.
 */
public final class SupportSafety {
    /** How far below the support a floor must be for the drop to count as safe. */
    private static final int DROP_SCAN = 3;

    private SupportSafety() {}

    /** True when {@code pos} is the block directly under the companion's feet. */
    public static boolean occupies(CompanionEntity companion, BlockPos pos) {
        AABB bounds = companion.getBoundingBox();
        double top = pos.getY() + 1.0;
        return Math.abs(bounds.minY - top) < 0.2
                && bounds.maxX > pos.getX()
                && bounds.minX < pos.getX() + 1.0
                && bounds.maxZ > pos.getZ()
                && bounds.minZ < pos.getZ() + 1.0;
    }

    /**
     * Whether breaking {@code support} leaves solid, non-lava ground within {@link #DROP_SCAN} blocks. Water is
     * fallen through; lava at any depth in the scan makes the drop unsafe.
     */
    public static boolean safeDrop(ServerLevel level, BlockPos support) {
        for (int dy = 1; dy <= DROP_SCAN; dy++) {
            BlockPos at = support.below(dy);
            BlockState state = level.getBlockState(at);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (!state.getCollisionShape(level, at).isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
