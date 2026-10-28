package dev.friendai.perception;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.ResourceObservation;
import dev.friendai.perception.resource.ResourceCandidates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Notable blocks around the companion, nearest of each kind, with no Minecraft types in the result. */
public final class LandmarkScanner {
    public static final int RADIUS = 16;
    public static final int VERTICAL = 8;
    public static final int LIMIT = 5;

    private static final List<String> PRIORITY =
            List.of("lava", "water", "bed", "crafting_table", "furnace", "anvil", "chest");

    private LandmarkScanner() {}

    /** The nearest landmark of each kind within the box, highest priority first, capped at {@link #LIMIT}. */
    public static List<Landmark> scan(ServerLevel level, BlockPos center) {
        Map<String, BlockPos> nearest = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = Math.max(level.getMinY(), center.getY() - VERTICAL);
        int maxY = Math.min(level.getMaxY(), center.getY() + VERTICAL);
        for (int x = center.getX() - RADIUS; x <= center.getX() + RADIUS; x++) {
            for (int z = center.getZ() - RADIUS; z <= center.getZ() + RADIUS; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    pos.set(x, y, z);
                    String kind = kind(level.getBlockState(pos));
                    if (kind == null) {
                        continue;
                    }
                    BlockPos found = pos.immutable();
                    BlockPos previous = nearest.get(kind);
                    if (previous == null || found.distSqr(center) < previous.distSqr(center)) {
                        nearest.put(kind, found);
                    }
                }
            }
        }
        List<Landmark> landmarks = new ArrayList<>();
        for (String kind : PRIORITY) {
            if (landmarks.size() >= LIMIT) {
                break;
            }
            BlockPos at = nearest.get(kind);
            if (at == null) {
                continue;
            }
            landmarks.add(new Landmark(
                    kind,
                    shortName(BuiltInRegistries.BLOCK.getKey(
                            level.getBlockState(at).getBlock())),
                    at.getX() + " " + at.getY() + " " + at.getZ(),
                    Math.sqrt(at.distSqr(center)),
                    RelativeDirection.compass(at.getX() - center.getX(), at.getZ() - center.getZ())));
        }
        return landmarks;
    }

    /** Builds landmarks only from blocks directly seen this tick. */
    public static List<Landmark> scan(CompanionEntity companion, ResourceCandidates candidates) {
        BlockPos center = companion.blockPosition();
        Map<String, ResourceObservation> nearest = new LinkedHashMap<>();
        for (ResourceObservation observation : candidates.confirmedRecent(companion, RADIUS)) {
            Identifier id = Identifier.tryParse(observation.blockId());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            String kind = kind(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState());
            if (kind == null) continue;
            ResourceObservation old = nearest.get(kind);
            var p = observation.location().pos();
            if (old == null
                    || distanceSqr(center, p.x(), p.y(), p.z())
                            < distanceSqr(
                                    center,
                                    old.location().pos().x(),
                                    old.location().pos().y(),
                                    old.location().pos().z())) {
                nearest.put(kind, observation);
            }
        }
        List<Landmark> result = new ArrayList<>();
        for (String kind : PRIORITY) {
            if (result.size() >= LIMIT) break;
            ResourceObservation observation = nearest.get(kind);
            if (observation == null) continue;
            var p = observation.location().pos();
            result.add(new Landmark(
                    kind,
                    shortName(Identifier.parse(observation.blockId())),
                    p.x() + " " + p.y() + " " + p.z(),
                    Math.sqrt(distanceSqr(center, p.x(), p.y(), p.z())),
                    RelativeDirection.compass(p.x() - center.getX(), p.z() - center.getZ())));
        }
        return List.copyOf(result);
    }

    private static double distanceSqr(BlockPos center, int x, int y, int z) {
        return center.distToCenterSqr(x + 0.5, y + 0.5, z + 0.5);
    }

    private static String kind(BlockState state) {
        if (state.is(Blocks.LAVA)) {
            return "lava";
        }
        if (state.is(Blocks.WATER)) {
            return "water";
        }
        if (state.is(BlockTags.BEDS)) {
            return "bed";
        }
        if (state.is(Blocks.CRAFTING_TABLE)) {
            return "crafting_table";
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER)) {
            return "furnace";
        }
        if (state.is(BlockTags.ANVIL)) {
            return "anvil";
        }
        if (state.is(Blocks.CHEST)
                || state.is(Blocks.TRAPPED_CHEST)
                || state.is(Blocks.ENDER_CHEST)
                || state.is(Blocks.BARREL)) {
            return "chest";
        }
        return null;
    }

    private static String shortName(Identifier id) {
        return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
    }
}
