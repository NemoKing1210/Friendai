package dev.friendai.perception.resource;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.knowledge.KnowledgeLimits;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Shares a bounded observation budget between loaded companions in rotating order. */
public final class ObservationScheduler {
    private final Map<UUID, ResourceObserver> observers = new HashMap<>();

    public void register(CompanionEntity companion) {
        observers.put(companion.getUUID(), new ResourceObserver(companion));
    }

    public void unregister(CompanionEntity companion) {
        observers.remove(companion.getUUID());
    }

    public ResourceObserver observer(CompanionEntity companion) {
        return observers.computeIfAbsent(companion.getUUID(), ignored -> new ResourceObserver(companion));
    }

    public void tick(MinecraftServer server, long tick) {
        List<CompanionEntity> companions = CompanionManager.get(server).all().stream()
                .filter(companion -> !companion.isRemoved())
                .sorted(Comparator.comparing(companion -> companion.getUUID().toString()))
                .toList();
        observers.keySet().removeIf(id -> companions.stream().noneMatch(companion -> companion.getUUID().equals(id)));
        for (CompanionEntity companion : companions) observer(companion).scheduleSweep(tick);
        if (companions.isEmpty()) return;

        long deadline = System.nanoTime() + 1_000_000L;
        KnowledgeLimits.ObservationBudget budget = KnowledgeLimits.observationBudget(deadline, System::nanoTime);
        int start = budget.roundRobinStart(companions.size(), tick);
        for (int offset = 0; offset < companions.size(); offset++) {
            if (budget.availableCells() == 0) break;
            CompanionEntity companion = companions.get((start + offset) % companions.size());
            UUID id = companion.getUUID();
            int rayAllowance = budget.availableRays(id);
            if (rayAllowance == 0) continue;
            ResourceObserver.ObservationBatch batch = observer(companion).tick(rayAllowance,
                    budget.availableCells(), deadline);
            if (!budget.consume(id, batch.raysCompleted(), batch.cellsVisited())) {
                throw new IllegalStateException("observer exceeded its shared work allowance");
            }
        }
    }
}
