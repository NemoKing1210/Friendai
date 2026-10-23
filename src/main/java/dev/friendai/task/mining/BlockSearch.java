package dev.friendai.task.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockSearch {
    private BlockSearch() {}

    /** The nearest matching, breakable block; see {@link #nearestN}. */
    public static Optional<BlockPos> nearest(
            ServerLevel level, BlockPos origin, int radius, BlockMatcher matcher, Set<BlockPos> blacklist) {
        List<BlockPos> found = nearestN(level, origin, radius, matcher, blacklist, 1);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Up to {@code limit} matching, breakable blocks in a cube of {@code radius} horizontally and
     * {@code min(radius, 16)} vertically around {@code origin}, nearest first (ties by x, then y, then z).
     * Skips unloaded chunks, blacklisted positions and blocks with a negative destroy speed.
     */
    public static List<BlockPos> nearestN(ServerLevel level, BlockPos origin, int radius, BlockMatcher matcher,
            Set<BlockPos> blacklist, int limit) {
        Comparator<BlockPos> order = Comparator.<BlockPos>comparingDouble(origin::distSqr)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ);
        PriorityQueue<BlockPos> best = new PriorityQueue<>(Math.max(1, limit), order.reversed());
        int vertical = Math.min(radius, 16);
        int minY = Math.max(level.getMinY(), origin.getY() - vertical);
        int maxY = Math.min(level.getMaxY(), origin.getY() + vertical);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() - radius; x <= origin.getX() + radius; x++) {
            for (int z = origin.getZ() - radius; z <= origin.getZ() + radius; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    if (best.size() >= limit) {
                        BlockPos worst = best.peek();
                        double distance = pos.distSqr(origin);
                        double worstDistance = worst.distSqr(origin);
                        if (distance > worstDistance || (distance == worstDistance && order.compare(pos, worst) >= 0)) {
                            continue;
                        }
                    }
                    BlockState state = level.getBlockState(pos);
                    if (matcher.test(state) && state.getDestroySpeed(level, pos) >= 0 && !blacklist.contains(pos)) {
                        best.add(pos.immutable());
                        if (best.size() > limit) {
                            best.poll();
                        }
                    }
                }
            }
        }
        List<BlockPos> result = new ArrayList<>(best);
        result.sort(order);
        return result;
    }
}
