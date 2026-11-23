package dev.friendai.server.cartography;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainRepository;
import dev.friendai.features.cartography.TerrainSampler;
import dev.friendai.features.cartography.minecraft.MinecraftTerrainSource;
import dev.friendai.features.companion.BoundedRotation;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    /** How many owners one sampling window may visit, so the total work does not grow with owner count. */
    public static final int OWNERS_PER_WINDOW = 4;

    private final TerrainRepository repository;
    private final Map<UUID, TerrainGrid> grids = new HashMap<>();
    private final Map<UUID, TerrainSampler.Cursor> cursors = new HashMap<>();
    private final Map<UUID, dev.friendai.features.cartography.WalkRoadAccumulator> walkers = new HashMap<>();
    private final BoundedRotation rotation = new BoundedRotation(OWNERS_PER_WINDOW);
    private long lastTick = Long.MIN_VALUE;
    private SamplerMetrics lastMetrics = new SamplerMetrics(0, 0, 0, 0, 0, 0, 0, Map.of());

    /** Two samples closer than this are one place, not a route. */
    private static final int ROAD_MIN_DISTANCE = 16;

    /** One sampling window's bounded work and timing, for profiling and tests. */
    public record SamplerMetrics(
            long tick,
            int ownersLoaded,
            int ownersScheduled,
            int ownersSampled,
            int columnsSampled,
            int columnsSkipped,
            long elapsedNanos,
            Map<UUID, Integer> sampledByOwner) {
        public SamplerMetrics {
            sampledByOwner = Map.copyOf(sampledByOwner);
        }

        /** The shared column allowance one window may spend. */
        public int columnAllowance() {
            return OWNERS_PER_WINDOW * MAX_CELLS_PER_TICK;
        }
    }

    public TerrainSamplerService(TerrainRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository);
    }

    public void tick(ServerRuntime runtime) {
        long startedNanos = System.nanoTime();
        int now = runtime.server().getTickCount();
        if (now == lastTick || now % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        lastTick = now;
        List<CompanionEntity> loaded = runtime.companions().all();
        List<CompanionEntity> visited = visited(loaded);
        if (visited.isEmpty()) {
            lastMetrics = new SamplerMetrics(
                    now, loaded.size(), 0, 0, 0, 0, Math.max(0, System.nanoTime() - startedNanos), Map.of());
            return;
        }
        int sampled = 0;
        int skipped = 0;
        int ownersSampled = 0;
        Map<UUID, Integer> sampledByOwner = new HashMap<>();
        for (CompanionEntity companion : visited) {
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
            TerrainSampler.Result result = sample(owner, level, x, z, level.getGameTime());
            sampled += result.sampled();
            skipped += result.skipped();
            if (result.sampled() > 0) {
                ownersSampled++;
                sampledByOwner.put(owner, result.sampled());
            }
        }
        lastMetrics = new SamplerMetrics(
                now,
                loaded.size(),
                visited.size(),
                ownersSampled,
                sampled,
                skipped,
                Math.max(0, System.nanoTime() - startedNanos),
                sampledByOwner);
    }

    private List<CompanionEntity> visited(List<CompanionEntity> loaded) {
        Map<UUID, CompanionEntity> byOwner = new HashMap<>();
        for (CompanionEntity companion : loaded) {
            UUID owner = companion.ownerUuid();
            if (owner != null) {
                byOwner.putIfAbsent(owner, companion);
            }
        }
        List<UUID> owners = byOwner.keySet().stream().sorted().toList();
        List<UUID> batch = rotation.next(owners);
        if (batch.isEmpty()) {
            return List.of();
        }
        return batch.stream().map(byOwner::get).toList();
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

    /** The last sampling window's bounded work and timing. */
    public SamplerMetrics lastMetrics() {
        return lastMetrics;
    }

    /** Drops the in-memory cache, for a logout/owner change; the repository keeps the saved record. */
    public void forget(UUID owner) {
        grids.remove(owner);
        cursors.remove(owner);
        walkers.remove(owner);
        rotation.invalidateMembership();
    }
}
