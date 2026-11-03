package dev.friendai.features.mining.excavation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counters for one excavation; the report is built only from these values. */
public final class ExcavationProgress {
    private int attempted;
    private int broken;
    private int skipped;
    private int refused;
    private int ticks;
    private final Map<String, Integer> taken = new LinkedHashMap<>();
    private final Map<String, Integer> reasons = new LinkedHashMap<>();

    public void markAttempted() {
        attempted++;
    }

    public void markBroken() {
        broken++;
    }

    public void markSkipped(String reason) {
        skipped++;
        reasons.merge(reason, 1, Integer::sum);
    }

    public void markRefused(String reason) {
        refused++;
        reasons.merge(reason, 1, Integer::sum);
    }

    public void markTick() {
        ticks++;
    }

    public void addTaken(String itemId, int count) {
        if (itemId != null && count > 0) {
            taken.merge(itemId, count, Integer::sum);
        }
    }

    public int attempted() {
        return attempted;
    }

    public int broken() {
        return broken;
    }

    public int skipped() {
        return skipped;
    }

    public int refused() {
        return refused;
    }

    public int ticks() {
        return ticks;
    }

    public Map<String, Integer> taken() {
        return Map.copyOf(taken);
    }

    public Map<String, Integer> reasons() {
        return Map.copyOf(reasons);
    }

    /** Restores the counters of a checkpoint; used only by the adapter's decoder. */
    public void restore(int broken, int skipped, Map<String, Integer> taken) {
        this.broken = Math.max(0, broken);
        this.skipped = Math.max(0, skipped);
        if (taken != null) {
            taken.forEach(this::addTaken);
        }
    }
}
