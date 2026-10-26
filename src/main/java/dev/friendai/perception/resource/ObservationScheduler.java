package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.knowledge.KnowledgeLimits;
import dev.friendai.knowledge.ResourceObservation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.server.MinecraftServer;

/** Shares a bounded observation budget between loaded companions in rotating order. */
public final class ObservationScheduler {
    private final Map<UUID, CompanionEntity> companions = new HashMap<>();
    private final Map<UUID, ResourceObserver> observers = new HashMap<>();
    private final BiConsumer<CompanionEntity, ResourceObservation> observationSink;
    private final BiConsumer<CompanionEntity, CaveEntranceObservation> caveEntranceSink;
    private TickMetrics lastMetrics = new TickMetrics(0, 0, 0, 0, 0, 0, 0, Map.of());

    public record TickMetrics(long tick, int companions, int scheduledRays, int completedRays, int visitedCells,
                              long elapsedNanos, int queuedJobs, Map<UUID, Integer> completedByCompanion) {
        public TickMetrics { completedByCompanion = Map.copyOf(completedByCompanion); }
    }

    public ObservationScheduler() { this((companion, observation) -> {}, (companion, entrance) -> {}); }

    public ObservationScheduler(BiConsumer<CompanionEntity, ResourceObservation> observationSink) {
        this(observationSink, (companion, entrance) -> {});
    }

    public ObservationScheduler(BiConsumer<CompanionEntity, ResourceObservation> observationSink,
            BiConsumer<CompanionEntity, CaveEntranceObservation> caveEntranceSink) {
        this.observationSink = observationSink;
        this.caveEntranceSink = caveEntranceSink;
    }

    public void register(CompanionEntity companion) {
        companions.put(companion.getUUID(), companion);
        observers.put(companion.getUUID(), new ResourceObserver(companion));
    }

    public void unregister(CompanionEntity companion) {
        companions.remove(companion.getUUID());
        observers.remove(companion.getUUID());
    }

    public ResourceObserver observer(CompanionEntity companion) {
        companions.put(companion.getUUID(), companion);
        return observers.computeIfAbsent(companion.getUUID(), ignored -> new ResourceObserver(companion));
    }

    public TickMetrics lastMetrics() { return lastMetrics; }

    public void tick(MinecraftServer server, long tick) {
        long startedNanos = System.nanoTime();
        List<CompanionEntity> loaded = companions.values().stream()
                .filter(companion -> !companion.isRemoved() && companion.level().getServer() == server)
                .sorted(Comparator.comparing(companion -> companion.getUUID().toString()))
                .toList();
        companions.keySet().removeIf(id -> loaded.stream().noneMatch(companion -> companion.getUUID().equals(id)));
        observers.keySet().removeIf(id -> loaded.stream().noneMatch(companion -> companion.getUUID().equals(id)));
        if (loaded.isEmpty()) {
            lastMetrics = new TickMetrics(tick, 0, 0, 0, 0,
                    Math.max(0, System.nanoTime() - startedNanos), 0, Map.of());
            return;
        }

        int start = (int) Math.floorMod(tick, loaded.size());
        int scheduledRays = 0;
        for (CompanionEntity companion : loaded) scheduledRays += observer(companion).scheduleSweep(tick);
        // Enqueuing a deterministic 128-ray sweep is setup work, not sight work. Start the
        // soft processing deadline only after every companion has had its sweep scheduled.
        long deadline = System.nanoTime() + 1_000_000L;
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
            ResourceObserver.ObservationBatch batch = observer(companion).tick(rayAllowance,
                    cellAllowance, deadline);
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
        int queuedJobs = loaded.stream().mapToInt(companion -> observer(companion).queuedJobs()).sum();
        lastMetrics = new TickMetrics(tick, loaded.size(), scheduledRays, completedRays, visitedCells,
                Math.max(0, System.nanoTime() - startedNanos), queuedJobs, completedByCompanion);
    }
}
