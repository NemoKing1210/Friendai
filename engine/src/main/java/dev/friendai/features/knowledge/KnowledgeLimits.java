package dev.friendai.features.knowledge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public record KnowledgeLimits(int memberObservations, int sites, int coverageEntries, int trailNodes, int aliases) {
    public static final KnowledgeLimits DEFAULTS = new KnowledgeLimits(16_384, 2_048, 4_096, 1_024, 2_048);

    public KnowledgeLimits {
        if (memberObservations < 0 || sites < 0 || coverageEntries < 0 || trailNodes < 0 || aliases < 0) {
            throw new IllegalArgumentException("knowledge limits must not be negative");
        }
    }

    public static ObservationBudget observationBudget(long deadlineNanos, LongSupplier nanoClock) {
        return new ObservationBudget(deadlineNanos, nanoClock);
    }

    /** Shared server-tick budget for passive, changed-face and focused observation jobs. */
    public static final class ObservationBudget {
        public static final int MAX_RAYS_PER_COMPANION = 32;
        public static final int MAX_RAYS_PER_TICK = 256;
        public static final int MAX_CELLS_PER_TICK = 128;
        private final long deadlineNanos;
        private final LongSupplier nanoClock;
        private final Map<UUID, Integer> raysByCompanion = new HashMap<>();
        private int raysUsed;
        private int cellsUsed;

        private ObservationBudget(long deadlineNanos, LongSupplier nanoClock) {
            this.deadlineNanos = deadlineNanos;
            this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        }

        public int roundRobinStart(int companionCount, long tick) {
            if (companionCount < 1) return 0;
            return (int) Math.floorMod(tick, companionCount);
        }

        public boolean tryRay(UUID companion) {
            Objects.requireNonNull(companion, "companion");
            if (deadlineReached()
                    || raysUsed >= MAX_RAYS_PER_TICK
                    || raysByCompanion.getOrDefault(companion, 0) >= MAX_RAYS_PER_COMPANION) return false;
            raysUsed++;
            raysByCompanion.merge(companion, 1, Integer::sum);
            return true;
        }

        public boolean tryCell() {
            if (deadlineReached() || cellsUsed >= MAX_CELLS_PER_TICK) return false;
            cellsUsed++;
            return true;
        }

        public int availableCells() {
            return deadlineReached() ? 0 : MAX_CELLS_PER_TICK - cellsUsed;
        }

        /** Hard-cap allowance for the round-robin first observer when the soft deadline just expired. */
        public int availableCellsIgnoringDeadline() {
            return MAX_CELLS_PER_TICK - cellsUsed;
        }

        public int availableRays(UUID companion) {
            Objects.requireNonNull(companion, "companion");
            if (deadlineReached()) return 0;
            return availableRaysIgnoringDeadline(companion);
        }

        /** Hard-cap allowance for one first-observer progress step after the soft deadline. */
        public int availableRaysIgnoringDeadline(UUID companion) {
            Objects.requireNonNull(companion, "companion");
            return Math.max(
                    0,
                    Math.min(
                            MAX_RAYS_PER_COMPANION - raysByCompanion.getOrDefault(companion, 0),
                            MAX_RAYS_PER_TICK - raysUsed));
        }

        /** Charges a completed observer batch against the same caps used by focused and passive work. */
        public boolean consume(UUID companion, int rays, int cells) {
            Objects.requireNonNull(companion, "companion");
            if (rays < 0
                    || cells < 0
                    || rays > availableRaysIgnoringDeadline(companion)
                    || cells > MAX_CELLS_PER_TICK - cellsUsed) return false;
            raysUsed += rays;
            cellsUsed += cells;
            if (rays > 0) raysByCompanion.merge(companion, rays, Integer::sum);
            return true;
        }

        public int raysUsed() {
            return raysUsed;
        }

        public int cellsUsed() {
            return cellsUsed;
        }

        private boolean deadlineReached() {
            return nanoClock.getAsLong() >= deadlineNanos;
        }
    }
}
