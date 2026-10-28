package dev.friendai.task.build;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.construction.blueprint.ClearCell;
import dev.friendai.features.construction.blueprint.ClearKind;
import dev.friendai.features.navigation.pathing.BlockList;
import dev.friendai.task.nav.LevelCellView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
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
    public enum SkipReason {
        PROTECTED("protected"),
        BLACKLISTED("blacklisted"),
        HAS_CONTENTS("has contents"),
        UNDER_FEET("under my feet"),
        UNBREAKABLE("unbreakable"),
        LAVA("lava"),
        FLOWING("flowing water"),
        NOT_WANTED("not selected"),
        NO_BUCKET("no empty bucket"),
        WATER_LEFT("water left"),
        UNREACHABLE("unreachable"),
        OUT_OF_SIGHT("out of sight"),
        CHANGED("changed");

        private final String label;

        SkipReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** @param solidBlocksLeft ordinary solid cells the caller did not ask to break */
    public record Result(List<ClearCell> candidates, Map<SkipReason, Integer> skipped, int solidBlocksLeft) {
        public Result {
            candidates = List.copyOf(candidates);
            skipped = Map.copyOf(skipped);
        }
    }

    private ClearScan() {}

    public static Result scan(
            ServerLevel level, CompanionEntity companion, BlockPos min, BlockPos max, boolean water, boolean blocks) {
        return scan(
                level,
                companion,
                min,
                max,
                water,
                blocks,
                companion.serverSettings().breakList());
    }

    public static Result scan(
            ServerLevel level,
            CompanionEntity companion,
            BlockPos min,
            BlockPos max,
            boolean water,
            boolean blocks,
            BlockList breakList) {
        List<ClearCell> candidates = new ArrayList<>();
        EnumMap<SkipReason, Integer> skipped = new EnumMap<>(SkipReason.class);
        int solids = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.isOutsideBuildHeight(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (!companion.hands().canInteract(pos)) {
                        skipped.merge(SkipReason.PROTECTED, 1, Integer::sum);
                        continue;
                    }
                    if (breakList.matches(LevelCellView.idOf(state.getBlock()), LevelCellView.tagsOf(state))) {
                        skipped.merge(SkipReason.BLACKLISTED, 1, Integer::sum);
                        continue;
                    }
                    if (pos.equals(companion.blockPosition().below())
                            || companion.getBoundingBox().intersects(new AABB(pos))) {
                        skipped.merge(SkipReason.UNDER_FEET, 1, Integer::sum);
                        continue;
                    }
                    if (state.hasBlockEntity()) {
                        skipped.merge(SkipReason.HAS_CONTENTS, 1, Integer::sum);
                        continue;
                    }
                    if (state.getDestroySpeed(level, pos) < 0) {
                        skipped.merge(SkipReason.UNBREAKABLE, 1, Integer::sum);
                        continue;
                    }
                    if (state.is(Blocks.LAVA)) {
                        skipped.merge(SkipReason.LAVA, 1, Integer::sum);
                        continue;
                    }
                    if (state.is(Blocks.WATER)) {
                        if (!state.getFluidState().isSource()) {
                            skipped.merge(SkipReason.FLOWING, 1, Integer::sum);
                        } else if (!water) {
                            skipped.merge(SkipReason.NOT_WANTED, 1, Integer::sum);
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
