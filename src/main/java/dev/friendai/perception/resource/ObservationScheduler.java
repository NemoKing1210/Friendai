package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.knowledge.KnowledgeLimits;
import dev.friendai.features.knowledge.ResourceObservation;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;
import net.minecraft.server.MinecraftServer;

/** Shares a bounded observation budget between loaded companions in rotating order. */
public final class ObservationScheduler {
    private final Map<UUID, CompanionEntity> companions = new HashMap<>();
    private final Map<UUID, ResourceObserver> observers = new HashMap<>();
    private final Set<UUID> loadedIds = new HashSet<>();
    private final BiConsumer<CompanionEntity, ResourceObservation> observationSink;
    private final BiConsumer<CompanionEntity, CaveEntranceObservation> caveEntranceSink;
    private final IntSupplier observationRadius;
    private TickMetrics lastMetrics = new TickMetrics(0, 0, 0, 0, 0, 0, 0, 0, Map.of());

    public record TickMetrics(
            long tick,
            int companions,
            int scheduledRays,
            int completedRays,
            int visitedCells,
            long elapsedNanos,
            long setupNanos,
            int queuedJobs,
            Map<UUID, Integer> completedByCompanion) {
        public TickMetrics {
            completedByCompanion = Map.copyOf(completedByCompanion);
        }
    }

    public ObservationScheduler() {
        this((companion, observation) -> {}, (companion, entrance) -> {});
    }

    public ObservationScheduler(BiConsumer<CompanionEntity, ResourceObservation> observationSink) {
        this(observationSink, (companion, entrance) -> {});
    }

    public ObservationScheduler(
            BiConsumer<CompanionEntity, ResourceObservation> observationSink,
            BiConsumer<CompanionEntity, CaveEntranceObservation> caveEntranceSink) {
        this(observationSink, caveEntranceSink, () -> 16);
    }

    public ObservationScheduler(
            BiConsumer<CompanionEntity, ResourceObservation> observationSink,
            BiConsumer<CompanionEntity, CaveEntranceObservation> caveEntranceSink,
            IntSupplier observationRadius) {
        this.observationSink = observationSink;
        this.caveEntranceSink = caveEntranceSink;
        this.observationRadius = observationRadius;
    }

    public void register(CompanionEntity companion) {
        companions.put(companion.getUUID(), companion);
        observers.put(companion.getUUID(), new ResourceObserver(companion, observationRadius));
    }

    public void unregister(CompanionEntity companion) {
        companions.remove(companion.getUUID());
        observers.remove(companion.getUUID());
    }

    public ResourceObserver observer(CompanionEntity companion) {
        companions.put(companion.getUUID(), companion);
        return observers.computeIfAbsent(
                companion.getUUID(), ignored -> new ResourceObserver(companion, observationRadius));
    }

    public TickMetrics lastMetrics() {
        return lastMetrics;
    }

    public void tick(MinecraftServer server, long tick) {
        long startedNanos = System.nanoTime();
        // Setup is part of the shared budget: filtering, sorting and enqueuing sweeps used to start the clock after
        // they finished, so a tick with many companions could spend the budget twice.
        long deadline = startedNanos + 1_000_000L;
        List<CompanionEntity> loaded = companions.values().stream()
                .filter(companion -> !companion.isRemoved() && companion.level().getServer() == server)
                .sorted(Comparator.comparing(companion -> companion.getUUID().toString()))
                .toList();
        loadedIds.clear();
        for (CompanionEntity companion : loaded) loadedIds.add(companion.getUUID());
        companions.keySet().removeIf(id -> !loadedIds.contains(id));
        observers.keySet().removeIf(id -> !loadedIds.contains(id));
        if (loaded.isEmpty()) {
            long elapsed = Math.max(0, System.nanoTime() - startedNanos);
            lastMetrics = new TickMetrics(tick, 0, 0, 0, 0, elapsed, elapsed, 0, Map.of());
            return;
        }

        int start = (int) Math.floorMod(tick, loaded.size());
        int scheduledRays = 0;
        for (CompanionEntity companion : loaded)
            scheduledRays += observer(companion).scheduleSweep(tick);
        long setupNanos = Math.max(0, System.nanoTime() - startedNanos);
        KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(deadline, System::nanoTime);
        boolean firstObserver = true;
        int completedRays = 0;
        int visitedCells = 0;
        Map<UUID, Integer> completedByCompanion = new HashMap<>();
        for (int offset = 0; offset < loaded.size(); offset++) {
            int cellAllowance = firstObserver ? budget.availableCellsIgnoringDeadline() : budget.availableCells();
            if (cellAllowance == 0) break;
            CompanionEntity companion = loaded.get((start + offset) % loaded.size());
            UUID id = companion.getUUID();
            int rayAllowance = firstObserver ? budget.availableRaysIgnoringDeadline(id) : budget.availableRays(id);
            if (rayAllowance == 0) continue;
            ResourceObserver.ObservationBatch batch = observer(companion).tick(rayAllowance, cellAllowance, deadline);
            if (!budget.consume(id, batch.raysCompleted(), batch.cellsVisited())) {
                throw new IllegalStateException("observer exceeded its shared work allowance");
            }
            completedRays += batch.raysCompleted();
            visitedCells += batch.cellsVisited();
            if (batch.raysCompleted() > 0) completedByCompanion.put(id, batch.raysCompleted());
            batch.observations().forEach(observation -> observationSink.accept(companion, observation));
            batch.caveEntrances().forEach(entrance -> caveEntranceSink.accept(companion, entrance));
            firstObserver = false;
        }
        int queuedJobs = loaded.stream()
                .mapToInt(companion -> observer(companion).queuedJobs())
                .sum();
        lastMetrics = new TickMetrics(
                tick,
                loaded.size(),
                scheduledRays,
                completedRays,
                visitedCells,
                Math.max(0, System.nanoTime() - startedNanos),
                setupNanos,
                queuedJobs,
                completedByCompanion);
    }
}
