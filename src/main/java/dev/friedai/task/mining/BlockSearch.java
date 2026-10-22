package dev.friedai.task.mining;

import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockSearch {
    private BlockSearch() {}

    /**
     * The nearest matching, breakable block in a cube of {@code radius} horizontally and
     * {@code min(radius, 16)} vertically around {@code origin}. Skips unloaded chunks and blacklisted positions.
     */
    public static Optional<BlockPos> nearest(
            ServerLevel level, BlockPos origin, int radius, BlockMatcher matcher, Set<BlockPos> blacklist) {
        int vertical = Math.min(radius, 16);
        int minY = Math.max(level.getMinY(), origin.getY() - vertical);
        int maxY = Math.min(level.getMaxY(), origin.getY() + vertical);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    double distance = pos.distSqr(origin);
                    if (distance >= bestDistance) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (matcher.test(state) && state.getDestroySpeed(level, pos) >= 0 && !blacklist.contains(pos)) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
