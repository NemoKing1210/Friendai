package dev.friendai.features.cartography.minecraft;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.cartography.TerrainGrid;
import dev.friendai.features.cartography.TerrainRepository;
import dev.friendai.features.cartography.TerrainSampler;
import dev.friendai.server.runtime.ServerRuntime;
import java.util.HashMap;
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

    private final TerrainRepository repository;
    private final Map<UUID, TerrainGrid> grids = new HashMap<>();
    private final Map<UUID, TerrainSampler.Cursor> cursors = new HashMap<>();
    private long lastTick = Long.MIN_VALUE;

    public TerrainSamplerService(TerrainRepository repository) {
        this.repository = java.util.Objects.requireNonNull(repository);
    }

    public void tick(ServerRuntime runtime) {
        long now = runtime.server().getTickCount();
        if (now == lastTick || now % SAMPLE_EVERY_TICKS != 0) {
            return;
        }
        lastTick = now;
        for (CompanionEntity companion : runtime.companions().all()) {
            UUID owner = companion.ownerUuid();
            if (owner == null || !(companion.level() instanceof ServerLevel level)) {
                continue;
            }
            sample(
                    owner,
                    level,
                    companion.blockPosition().getX(),
                    companion.blockPosition().getZ(),
                    level.getGameTime());
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
    }
}
