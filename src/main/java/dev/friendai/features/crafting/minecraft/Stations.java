package dev.friendai.features.crafting.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.crafting.Station;
import dev.friendai.features.mining.minecraft.mining.BlockMatcher;
import dev.friendai.perception.resource.ResourceCandidates;
import dev.friendai.task.TaskContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Finding crafting stations around the companion and judging whether a place looks like a base. */
public final class Stations {
    /** How far the companion looks for a station it can use. */
    public static final int SEARCH_RADIUS = 16;

    /** How far the base check looks around the station. */
    public static final int BASE_RADIUS = 8;

    /** How many different kinds of settlement blocks make a place look like a base. */
    public static final int BASE_KINDS = 2;

    private static final int BED = 1;
    private static final int STORAGE = 2;
    private static final int FURNACE = 4;
    private static final int DOOR = 8;
    private static final int STATION = 16;

    private Stations() {}

    /** Evidence-backed station candidates for companion decisions. */
    public static List<BlockPos> nearbyAll(CompanionEntity companion, Station station, int limit) {
        if (!(companion.level() instanceof ServerLevel level) || block(station) == null) return List.of();
        ResourceCandidates candidates = new TaskContext(companion, level).resources();
        return candidates.knownOrVisible(companion, matcher(station), SEARCH_RADIUS, limit);
    }

    public static Optional<BlockPos> nearby(CompanionEntity companion, Station station) {
        List<BlockPos> found = nearbyAll(companion, station, 1);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static BlockMatcher matcher(Station station) {
        Block block = block(station);
        return new BlockMatcher() {
            @Override
            public boolean test(BlockState state) {
                if (station == Station.CAMPFIRE) {
                    return state.is(BlockTags.CAMPFIRES)
                            && state.hasProperty(CampfireBlock.LIT)
                            && state.getValue(CampfireBlock.LIT);
                }
                return state.is(block);
            }

            @Override
            public String label() {
                return station == Station.CAMPFIRE
                        ? "#minecraft:campfires"
                        : BuiltInRegistries.BLOCK.getKey(block).toString();
            }
        };
    }

    /** The stations the companion can use where it stands, for the planner. */
    public static Set<Station> available(ServerLevel level, CompanionEntity companion) {
        Set<Station> available = EnumSet.of(Station.INVENTORY);
        for (Station station : Station.values()) {
            if (station != Station.INVENTORY && nearby(companion, station).isPresent()) {
                available.add(station);
            }
        }
        return available;
    }

    /**
     * True when at least {@link #BASE_KINDS} kinds of settlement blocks are within {@link #BASE_RADIUS}: a bed, a
     * storage container, a furnace, a door and a crafting station other than the block at {@code pos} itself.
     */
    public static boolean looksLikeBase(ServerLevel level, BlockPos pos) {
        int minY = Math.max(level.getMinY(), pos.getY() - BASE_RADIUS);
        int maxY = Math.min(level.getMaxY(), pos.getY() + BASE_RADIUS);
        int kinds = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = pos.getX() - BASE_RADIUS; x <= pos.getX() + BASE_RADIUS; x++) {
            for (int z = pos.getZ() - BASE_RADIUS; z <= pos.getZ() + BASE_RADIUS; z++) {
                if (!level.hasChunkAt(x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    int kind = kind(level.getBlockState(cursor), cursor.equals(pos));
                    if (kind != 0 && (kinds & kind) == 0) {
                        kinds |= kind;
                        if (Integer.bitCount(kinds) >= BASE_KINDS) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Evaluates base features only from direct observations, for production station selection. */
    public static boolean looksLikeBase(CompanionEntity companion, BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel level)) return false;
        var observations = new TaskContext(companion, level).resources().confirmedRecent(companion, BASE_RADIUS * 2);
        int kinds = 0;
        for (var observation : observations) {
            var p = observation.location().pos();
            BlockPos seen = new BlockPos(p.x(), p.y(), p.z());
            if (seen.equals(pos)
                    || Math.abs(seen.getX() - pos.getX()) > BASE_RADIUS
                    || Math.abs(seen.getY() - pos.getY()) > BASE_RADIUS
                    || Math.abs(seen.getZ() - pos.getZ()) > BASE_RADIUS) continue;
            var id = net.minecraft.resources.Identifier.tryParse(observation.blockId());
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) continue;
            int kind = kind(BuiltInRegistries.BLOCK.getValue(id).defaultBlockState(), false);
            kinds |= kind;
            if (Integer.bitCount(kinds) >= BASE_KINDS) return true;
        }
        return false;
    }

    /** The block a station is made of; {@code null} for {@link Station#INVENTORY}. */
    public static Block block(Station station) {
        return switch (station) {
            case INVENTORY -> null;
            case CRAFTING_TABLE -> Blocks.CRAFTING_TABLE;
            case STONECUTTER -> Blocks.STONECUTTER;
            case SMITHING_TABLE -> Blocks.SMITHING_TABLE;
            case FURNACE -> Blocks.FURNACE;
            case SMOKER -> Blocks.SMOKER;
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
            case CAMPFIRE -> Blocks.CAMPFIRE;
        };
    }

    private static int kind(BlockState state, boolean atPosition) {
        if (state.is(BlockTags.BEDS)) {
            return BED;
        }
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.BARREL)) {
            return STORAGE;
        }
        if (state.is(Blocks.FURNACE) || state.is(Blocks.SMOKER) || state.is(Blocks.BLAST_FURNACE)) {
            return FURNACE;
        }
        if (state.is(BlockTags.DOORS)) {
            return DOOR;
        }
        if (!atPosition && isStation(state)) {
            return STATION;
        }
        return 0;
    }

    private static boolean isStation(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE)
                || state.is(Blocks.STONECUTTER)
                || state.is(Blocks.SMITHING_TABLE)
                || state.is(BlockTags.ANVIL)
                || state.is(Blocks.LOOM)
                || state.is(Blocks.CARTOGRAPHY_TABLE)
                || state.is(Blocks.GRINDSTONE)
                || state.is(Blocks.BREWING_STAND)
                || state.is(Blocks.ENCHANTING_TABLE);
    }
}
