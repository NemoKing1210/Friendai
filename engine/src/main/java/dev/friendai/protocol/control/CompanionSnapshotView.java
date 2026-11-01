package dev.friendai.protocol.control;

import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import java.util.List;

/** Bounded protocol representation of the companion control view. */
public record CompanionSnapshotView(
        boolean present,
        boolean operator,
        String companionName,
        String provider,
        String model,
        boolean thinking,
        String currentTask,
        List<TaskRecord> history,
        List<LogEntry> newLog,
        String perception,
        List<String> toolNames,
        Vitals vitals,
        List<QueueItem> queue,
        boolean autoRespawn,
        boolean autoRespawnAvailable,
        boolean creativeMode,
        boolean creativeModeAvailable,
        boolean freeTimeOn,
        String freeTimeActivity,
        boolean freeTimeWaiting,
        boolean followMode,
        String freeTimeNext,
        String freeTimeBudget,
        String freeTimeLast,
        String freeTimePlannerModel,
        String bondTier,
        List<String> bondReasons,
        String goalText,
        List<String> goalCriteria,
        List<String> goalUnmet) {

    /**
     * A payload written before these fields existed, or by a peer that leaves them out, decodes with them empty:
     * Gson fills a missing component with {@code null} and would otherwise fail the codec's bounds check.
     */
    public CompanionSnapshotView {
        bondTier = bondTier == null ? "" : bondTier;
        bondReasons = bondReasons == null ? List.of() : List.copyOf(bondReasons);
        goalText = goalText == null ? "" : goalText;
        goalCriteria = goalCriteria == null ? List.of() : List.copyOf(goalCriteria);
        goalUnmet = goalUnmet == null ? List.of() : List.copyOf(goalUnmet);
    }

    public record Vitals(
            float health,
            float maxHealth,
            int food,
            int inventoryUsed,
            int inventorySlots,
            String protection,
            String style,
            String action,
            String reason,
            String paceMode,
            int xpLevel,
            float xpProgress) {}

    public record QueueItem(
            long id,
            long planId,
            String planGoal,
            String planStatus,
            String tool,
            String args,
            String state,
            String note,
            List<Long> after,
            int attempts,
            int maxAttempts,
            int priority) {}
}
