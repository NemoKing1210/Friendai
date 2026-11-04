package dev.friendai.features.construction.minecraft.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.blueprint.ClearCell;
import dev.friendai.features.construction.blueprint.ClearKind;
import dev.friendai.features.construction.clear.ClearSkip;
import dev.friendai.features.navigation.minecraft.nav.LevelCellView;
import dev.friendai.foundation.spatial.BlockList;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Reads a box of the world and says what a clear-area task may empty and what it must leave alone. The rules are the
 * deliberate ones from the spec: protection and the break blacklist first, a block entity never (its contents cannot
 * be put back), the companion's own body never, and then the kind — vegetation and snow by default, water sources only
 * with a bucket, solid blocks only when the caller asked for them. Lava and flowing water are never candidates.
 */
public final class ClearScan {
    /** @param solidBlocksLeft ordinary solid cells the caller did not ask to break */
    public record Result(List<ClearCell> candidates, Map<ClearSkip, Integer> skipped, int solidBlocksLeft) {
        public Result {
            candidates = List.copyOf(candidates);
            skipped = Map.copyOf(skipped);
        }
    }

    private ClearScan() {}

    public static Result scan(
            ServerLevel level,
            CompanionEntity companion,
            BlockPos min,
            BlockPos max,
            boolean water,
            boolean blocks,
            BlockList breakList) {
        List<ClearCell> candidates = new ArrayList<>();
        EnumMap<ClearSkip, Integer> skipped = new EnumMap<>(ClearSkip.class);
        Set<Long> loaded = loadedChunks(level, min, max);
        // Tags belong to the block, not to the state standing there, so one entry per block serves the whole scan.
        Map<Block, Set<String>> tagsByBlock = new HashMap<>();
        int solids = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isOutsideBuildHeight(pos)) {
                        continue;
                    }
                    // Reading a block of an unloaded chunk generates the chunk, so the part that is not loaded is
                    // counted as skipped before the first world read.
                    if (!loaded.contains(ChunkPos.pack(x >> 4, z >> 4))) {
                        skipped.merge(ClearSkip.NOT_LOADED, 1, Integer::sum);
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!companion.hands().canInteract(pos)) {
                        skipped.merge(ClearSkip.PROTECTED, 1, Integer::sum);
                        continue;
                    }
                    if (!breakList.isEmpty()
                            && breakList.matches(
                                    LevelCellView.idOf(state.getBlock()),
                                    tagsByBlock.computeIfAbsent(
                                            state.getBlock(),
                                            block -> LevelCellView.tagsOf(block.defaultBlockState())))) {
                        skipped.merge(ClearSkip.BLACKLISTED, 1, Integer::sum);
                        continue;
                    }
                    if (pos.equals(companion.blockPosition().below())
                            || companion.getBoundingBox().intersects(new AABB(pos))) {
                        skipped.merge(ClearSkip.UNDER_FEET, 1, Integer::sum);
                        continue;
                    }
                    if (state.hasBlockEntity()) {
                        skipped.merge(ClearSkip.HAS_CONTENTS, 1, Integer::sum);
                        continue;
                    }
                    if (state.getDestroySpeed(level, pos) < 0) {
                        skipped.merge(ClearSkip.UNBREAKABLE, 1, Integer::sum);
                        continue;
                    }
                    if (state.is(Blocks.LAVA)) {
                        skipped.merge(ClearSkip.LAVA, 1, Integer::sum);
                        continue;
                    }
                    if (state.is(Blocks.WATER)) {
                        if (!state.getFluidState().isSource()) {
                            skipped.merge(ClearSkip.FLOWING, 1, Integer::sum);
                        } else if (!water) {
                            skipped.merge(ClearSkip.NOT_WANTED, 1, Integer::sum);
                        } else {
                            candidates.add(new ClearCell(x, y, z, ClearKind.WATER));
                        }
                        continue;
                    }
                    if (state.is(BlockTags.SNOW) || state.is(Blocks.POWDER_SNOW)) {
                        candidates.add(new ClearCell(x, y, z, ClearKind.SNOW));
                        continue;
                    }
                    if (isVegetation(state)) {
                        candidates.add(new ClearCell(x, y, z, ClearKind.VEGETATION));
                        continue;
                    }
                    if (blocks) {
                        candidates.add(new ClearCell(x, y, z, ClearKind.BLOCK));
                    } else {
                        solids++;
                    }
                }
            }
        }
        return new Result(candidates, skipped, solids);
    }

    /**
     * The chunks of the box that are already loaded, in chunk coordinates. A query never generates a chunk, so the
     * grid is at most a handful of entries for the largest area the task accepts.
     */
    private static Set<Long> loadedChunks(ServerLevel level, BlockPos min, BlockPos max) {
        ServerChunkCache chunks = level.getChunkSource();
        Set<Long> loaded = new HashSet<>();
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                if (chunks.hasChunk(cx, cz)) {
                    loaded.add(ChunkPos.pack(cx, cz));
                }
            }
        }
        return loaded;
    }

    private static boolean isVegetation(BlockState state) {
        if (state.is(BlockTags.CROPS)) {
            return false;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.canBeReplaced();
    }
}
