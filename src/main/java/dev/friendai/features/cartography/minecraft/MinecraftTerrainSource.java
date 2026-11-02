package dev.friendai.features.cartography.minecraft;

import dev.friendai.features.cartography.TerrainSampler;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Server-thread adapter: a column is readable only when its chunk is already loaded; nothing here requests a load. */
public final class MinecraftTerrainSource implements TerrainSampler.Source {
    private final ServerLevel level;
    private final String dimension;

    public MinecraftTerrainSource(ServerLevel level) {
        this.level = java.util.Objects.requireNonNull(level);
        this.dimension = level.dimension().identifier().toString();
    }

    @Override
    public boolean loaded(int x, int z) {
        return level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
    }

    @Override
    public Optional<TerrainSampler.Observation> observe(int x, int z) {
        if (!loaded(x, z)) {
            return Optional.empty();
        }
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (height <= level.getMinY()) {
            return Optional.empty();
        }
        int surfaceY = height - 1;
        BlockPos pos = new BlockPos(x, surfaceY, z);
        String biome = level.getBiome(pos)
                .unwrapKey()
                .map(key -> key.identifier().toString())
                .orElse("");
        boolean water = !level.getFluidState(pos).isEmpty();
        return Optional.of(new TerrainSampler.Observation(dimension, x, z, biome, surfaceY, water, ""));
    }
}
