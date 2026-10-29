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
        String freeTimePlannerModel) {

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
