package dev.friendai.features.construction.clear;

import dev.friendai.features.construction.blueprint.ClearKind;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One clear-area run in counts: what was cleared, what it tried and failed to clear, and what it deliberately left
 * alone. Refusals by design — lava, flowing water, a water source the caller excluded, a cell that changed before the
 * break, and the block the companion stands on — are not failures, so the task's status is a function of this record.
 */
public record ClearOutcome(
        Map<ClearKind, Integer> cleared,
        Map<ClearSkip, Integer> failed,
        Map<ClearSkip, Integer> leftByDesign,
        int solidBlocksLeft,
        int cellsLeftForNextPass) {
    private static final Set<ClearSkip> BY_DESIGN = EnumSet.of(
            ClearSkip.LAVA, ClearSkip.FLOWING, ClearSkip.NOT_WANTED, ClearSkip.CHANGED, ClearSkip.UNDER_FEET);

    public ClearOutcome {
        cleared = copyPositive(cleared, ClearKind.class);
        failed = copyPositive(failed, ClearSkip.class);
        leftByDesign = copyPositive(leftByDesign, ClearSkip.class);
    }

    /** Splits the scanner's skip counts into refusals by design and attempts that did not work. */
    public static ClearOutcome of(
            Map<ClearKind, Integer> cleared,
            Map<ClearSkip, Integer> skipped,
            int solidBlocksLeft,
            int cellsLeftForNextPass) {
        EnumMap<ClearSkip, Integer> failed = new EnumMap<>(ClearSkip.class);
        EnumMap<ClearSkip, Integer> leftByDesign = new EnumMap<>(ClearSkip.class);
        skipped.forEach((reason, count) -> {
            if (BY_DESIGN.contains(reason)) {
                leftByDesign.merge(reason, count, Integer::sum);
            } else {
                failed.merge(reason, count, Integer::sum);
            }
        });
        return new ClearOutcome(cleared, failed, leftByDesign, solidBlocksLeft, cellsLeftForNextPass);
    }

    public int clearedTotal() {
        return total(cleared);
    }

    public int failedTotal() {
        return total(failed);
    }

    public int leftByDesignTotal() {
        return total(leftByDesign);
    }

    public boolean nothingToDo() {
        return clearedTotal() == 0
                && failedTotal() == 0
                && leftByDesignTotal() == 0
                && solidBlocksLeft() == 0
                && cellsLeftForNextPass() == 0;
    }

    /** A run that cleared nothing but tried cells it could not clear. */
    public boolean failedRun() {
        return clearedTotal() == 0 && failedTotal() > 0;
    }

    /** The cleared kinds in {@link ClearKind} declaration order, so a summary is stable. */
    public List<ClearKind> clearedKinds() {
        return Arrays.stream(ClearKind.values())
                .filter(kind -> cleared.getOrDefault(kind, 0) > 0)
                .toList();
    }

    private static int total(Map<?, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static <K extends Enum<K>> Map<K, Integer> copyPositive(Map<K, Integer> counts, Class<K> type) {
        EnumMap<K, Integer> kept = new EnumMap<>(type);
        counts.forEach((key, count) -> {
            if (count != null && count > 0) {
                kept.put(key, count);
            }
        });
        return Map.copyOf(kept);
    }
}
