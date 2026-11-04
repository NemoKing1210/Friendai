package dev.friendai.features.cartography.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainRepository;
import dev.friendai.features.cartography.TerrainSampler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Ticks the loaded-only terrain sampler for every loaded companion. Work is bounded to one small batch every
 * {@link #SAMPLE_EVERY_TICKS} ticks, so a large radius is covered over time without a per-tick scan. The grid is
 * cached per owner and handed back to the repository for the owner's debounced flush.
 */
public final class TerrainSamplerService {
    public static final int SAMPLE_EVERY_TICKS = 20;
    public static final int RADIUS = 8;
    public static final int MAX_CELLS_PER_TICK = 8;

    private final TerrainRepository repository;
    private final Map<UUID, TerrainGrid> grids = new HashMap<>();
    private final Map<UUID, TerrainSampler.Cursor> cursors = new HashMap<>();
    private final Map<UUID, dev.friendai.features.cartography.WalkRoadAccumulator> walkers = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    /** Two samples closer than this are one place, not a route. */
    private static final int ROAD_MIN_DISTANCE = 16;

    public TerrainSamplerService(TerrainRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository);
    }

    public void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now == lastTick || now % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        lastTick = now;
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null || !(companion.level() instanceof ServerLevel level)) {
                continue;
            }
            int x = companion.blockPosition().getX();
            int z = companion.blockPosition().getZ();
            String dimension = level.dimension().identifier().toString();
            TerrainGrid grid = grid(owner);
            // The route end is the last recorded one, not the previous sample, so a walk sampled every few blocks
            // still adds up instead of comparing each short step with the one before it.
            dev.friendai.features.cartography.WalkRoadAccumulator walk = walkers.computeIfAbsent(
                    owner, ignored -> new dev.friendai.features.cartography.WalkRoadAccumulator(ROAD_MIN_DISTANCE));
            walk.observe(dimension, x, z);
            walk.take().ifPresent(segment -> {
                grid.addRoad(new dev.friendai.features.cartography.TerrainRoad(
                        segment.dimension(),
                        segment.fromX(),
                        segment.fromZ(),
                        segment.toX(),
                        segment.toZ(),
                        "walked",
                        level.getGameTime()));
                repository.save(owner, grid);
            });
            sample(owner, level, x, z, level.getGameTime());
        }
    }

    /** One bounded batch for one owner; exposed for tests and for a manual sample. */
    public TerrainSampler.Result sample(UUID owner, ServerLevel level, int centerX, int centerZ, long tick) {
        TerrainGrid grid = grid(owner);
        TerrainSampler.Cursor cursor = cursors.getOrDefault(owner, TerrainSampler.Cursor.start(RADIUS));
        TerrainSampler.Result result = TerrainSampler.sample(
                grid, new MinecraftTerrainSource(level), cursor, centerX, centerZ, RADIUS, MAX_CELLS_PER_TICK, tick);
        cursors.put(owner, result.next());
        if (result.sampled() > 0) {
            repository.save(owner, grid);
        }
        return result;
    }

    /** The live grid for an owner; loaded from the repository on first use. */
    public TerrainGrid grid(UUID owner) {
        return grids.computeIfAbsent(owner, repository::grid);
    }

    /** Drops the in-memory cache, for a logout/owner change; the repository keeps the saved record. */
    public void forget(UUID owner) {
        grids.remove(owner);
        cursors.remove(owner);
        walkers.remove(owner);
    }
}
