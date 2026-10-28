package dev.friendai.task.obtain;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.navigation.pathing.Pos;
import dev.friendai.knowledge.search.SearchPlanner;
import dev.friendai.knowledge.travel.TrailGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

/** Samples the terrain facts a frontier score needs, bounded to a 3x3 column around the candidate. */
public final class SearchFrontierFacts {
    private SearchFrontierFacts() {}

    public static SearchPlanner.Terrain sample(
            CompanionEntity companion, Pos origin, Pos candidate, TrailGraph trails) {
        ServerLevel level = (ServerLevel) companion.level();
        int water = 0, hazard = 0, columns = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                columns++;
                BlockPos feetPos = new BlockPos(candidate.x() + dx, candidate.y(), candidate.z() + dz);
                if (!level.hasChunkAt(feetPos)) {
                    hazard++;
                    continue;
                }
                BlockState feet = level.getBlockState(feetPos);
                BlockState below = level.getBlockState(feetPos.below());
                if (feet.getFluidState().getType() == Fluids.WATER) water++;
                if (feet.getFluidState().getType() == Fluids.LAVA
                        || feet.is(Blocks.FIRE)
                        || feet.is(Blocks.SOUL_FIRE)
                        || !below.isSolid()) hazard++;
            }
        }
        String originBiome = SearchTargets.biomeAt(level, origin);
        String biome = SearchTargets.biomeAt(level, candidate);
        boolean transition = !originBiome.isEmpty() && !originBiome.equals(biome);
        boolean onTrail = trails != null
                && trails.hasNodeNear(level.dimension().identifier().toString(), candidate, 12);
        return new SearchPlanner.Terrain(
                transition, water / (double) columns, hazard / (double) columns, onTrail, biome);
    }
}
