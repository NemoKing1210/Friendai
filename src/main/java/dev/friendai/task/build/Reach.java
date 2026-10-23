package dev.friendai.task.build;

import dev.friendai.task.nav.Approach;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Whether the companion can stand close enough to every block of a blueprint it builds bottom-up.
 *
 * <p>The structure itself is the scaffolding: a block high on a wall or a roof is reachable once the blocks below it
 * have been placed, because the companion can stand on them. Only blocks with no standing spot within reach, on the
 * ground or on the part of the blueprint built before them, make a structure impossible.
 */
public final class Reach {
    /** How far above the origin a block may be before it needs its own standing spot. */
    public static final int HEIGHT = 4;

    /** The companion is 1.8 blocks tall; its eyes sit this far above its feet. */
    private static final double EYE_HEIGHT = 1.53;

    private Reach() {}

    /** The first spec with no standing spot within reach, or null when every block can be reached. */
    public static BlockPos firstOutOfReach(
            ServerLevel level, List<Placement.PlacedSpec> specs, BlockPos origin) {
        Set<BlockPos> planned = new HashSet<>();
        for (Placement.PlacedSpec spec : specs) {
            if (!spec.wanted().isAir()) {
                planned.add(spec.pos());
            }
        }
        for (Placement.PlacedSpec spec : specs) {
            if (spec.wanted().isAir() || spec.pos().getY() - origin.getY() <= HEIGHT) {
                continue;
            }
            if (!reachable(level, spec.pos(), planned)) {
                return spec.pos();
            }
        }
        return null;
    }

    /**
     * Whether some free cell within reach has a floor: either the blueprint's own blocks below (placed earlier, since
     * the order is bottom-up) or solid ground.
     */
    private static boolean reachable(ServerLevel level, BlockPos pos, Set<BlockPos> planned) {
        Vec3 target = Vec3.atCenterOf(pos);
        int span = (int) Approach.REACH;
        for (int dy = 0; dy >= -HEIGHT; dy--) {
            for (int dx = -span; dx <= span; dx++) {
                for (int dz = -span; dz <= span; dz++) {
                    BlockPos feet = pos.offset(dx, dy, dz);
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + EYE_HEIGHT, feet.getZ() + 0.5);
                    if (eye.distanceTo(target) > Approach.REACH) {
                        continue;
                    }
                    if (planned.contains(feet) || planned.contains(feet.above())) {
                        continue;
                    }
                    if (free(level, feet) && standing(level, feet.below(), planned)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir();
    }

    /** A block the companion can stand on: planned and not air, or world ground. */
    private static boolean standing(ServerLevel level, BlockPos pos, Set<BlockPos> planned) {
        if (planned.contains(pos)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced();
    }
}
