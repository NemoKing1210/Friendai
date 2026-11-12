package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.foundation.spatial.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * One deliberate look for a kind of block the companion has not seen yet, the way a player who is asked about something
 * turns and scans the area. Candidate cells are found in loaded chunks, but each one is recorded only when a real sight
 * ray from the companion's eyes reaches it, so a block behind a wall or inside a hill stays unseen. It covers the first
 * seconds after the companion arrives somewhere, before its passive sweeps have looked in every direction: asked "mine
 * that diamond" right after a summon, it used to answer that it saw no diamond ore beside one in plain view. Server
 * thread only.
 */
public final class FocusedLook {
    /** The widest look, in blocks: a deliberate glance, not a survey of the whole loaded world. */
    public static final int MAX_RADIUS = 32;

    /** How many of the nearest candidates get a sight ray. */
    private static final int MAX_CANDIDATES = 24;

    /** A cap on the cells collected before sorting, so a common block cannot turn a glance into a full scan. */
    private static final int MAX_COLLECTED = 2_048;

    private static final int RAY_CELLS = 128;
    private static final int RAY_STEPS = 4;

    /** Aim points inside the block: its centre, then the middle of each face, so a block seen edge-on still counts. */
    private static final double[][] AIM = {
        {0.5, 0.5, 0.5},
        {0.5, 0.9, 0.5},
        {0.1, 0.5, 0.5},
        {0.9, 0.5, 0.5},
        {0.5, 0.5, 0.1},
        {0.5, 0.5, 0.9},
        {0.5, 0.1, 0.5}
    };

    private FocusedLook() {}

    /** Looks for blocks the predicate accepts within the radius; returns how many were seen and recorded. */
    public static int lookFor(
            ServerLevel level,
            CompanionEntity companion,
            ResourceCandidates resources,
            Predicate<BlockState> wanted,
            int radius) {
        if (companion.ownerUuid() == null) return 0;
        int reach = Math.max(1, Math.min(MAX_RADIUS, radius));
        Vec3 eye = companion.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        List<BlockPos> candidates = candidates(level, center, reach, wanted);
        candidates.sort(Comparator.<BlockPos>comparingDouble(pos -> pos.distToCenterSqr(eye))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ));
        String dimension = level.dimension().identifier().toString();
        BlockPos viewpoint = companion.blockPosition();
        long tick = level.getServer().overworld().getGameTime();
        int seen = 0;
        for (int i = 0; i < Math.min(MAX_CANDIDATES, candidates.size()); i++) {
            BlockPos pos = candidates.get(i);
            if (!visible(level, eye, pos)) continue;
            resources.observe(
                    companion,
                    new ResourceObservation(
                            BuiltInRegistries.BLOCK
                                    .getKey(level.getBlockState(pos).getBlock())
                                    .toString(),
                            new ResourceObservation.Location(dimension, new Pos(pos.getX(), pos.getY(), pos.getZ())),
                            new Pos(viewpoint.getX(), viewpoint.getY(), viewpoint.getZ()),
                            tick));
            seen++;
        }
        return seen;
    }

    private static List<BlockPos> candidates(
            ServerLevel level, BlockPos center, int reach, Predicate<BlockState> wanted) {
        List<BlockPos> found = new ArrayList<>();
        double limit = (double) reach * reach;
        for (int chunkX = (center.getX() - reach) >> 4; chunkX <= (center.getX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - reach) >> 4; chunkZ <= (center.getZ() + reach) >> 4; chunkZ++) {
                BlockPos sample = new BlockPos(chunkX << 4, center.getY(), chunkZ << 4);
                if (!level.hasChunkAt(sample)) continue;
                LevelChunk chunk = level.getChunkAt(sample);
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(wanted)) continue;
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                    if (baseY + 15 < center.getY() - reach || baseY > center.getY() + reach) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!wanted.test(section.getBlockState(x, y, z))) continue;
                                BlockPos pos = new BlockPos((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                                if (center.distSqr(pos) > limit) continue;
                                found.add(pos);
                                if (found.size() >= MAX_COLLECTED) return found;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /** Whether a sight ray from the eye reaches the block before anything else stops it. */
    private static boolean visible(ServerLevel level, Vec3 eye, BlockPos pos) {
        for (double[] aim : AIM) {
            Vec3 endpoint = new Vec3(pos.getX() + aim[0], pos.getY() + aim[1], pos.getZ() + aim[2]);
            ResourceSight.Job sight = ResourceSight.rayTo(level, eye, endpoint, pos);
            ResourceSight.Outcome outcome = ResourceSight.Outcome.PENDING;
            for (int step = 0; step < RAY_STEPS && outcome == ResourceSight.Outcome.PENDING; step++) {
                outcome = sight.step(RAY_CELLS);
            }
            if (outcome == ResourceSight.Outcome.VISIBLE && pos.equals(sight.hitBlock())) return true;
        }
        return false;
    }
}
