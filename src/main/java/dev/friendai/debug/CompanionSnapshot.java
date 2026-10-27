package dev.friendai.debug;

import java.util.List;

/**
 * What the server tells a menu client about its companion. Log, perception and tool names are operator-only.
 *
 * @param freeTimeOn the companion is working on its own
 * @param freeTimeActivity what it is doing in free time, empty between activities
 */
public record CompanionSnapshot(
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
        CompanionVitals vitals,
        List<QueueRow> queue,
        boolean autoRespawn,
        boolean autoRespawnAvailable,
        boolean freeTimeOn,
        String freeTimeActivity) {
    public static final int MAX_ACTIVITY = 200;

    public CompanionSnapshot {
        freeTimeActivity = freeTimeActivity == null ? ""
                : freeTimeActivity.length() > MAX_ACTIVITY ? freeTimeActivity.substring(0, MAX_ACTIVITY)
                : freeTimeActivity;
    }

    public CompanionSnapshot(
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
            CompanionVitals vitals,
            List<QueueRow> queue,
            boolean autoRespawn,
            boolean autoRespawnAvailable) {
        this(present, operator, companionName, provider, model, thinking, currentTask, history, newLog, perception,
                toolNames, vitals, queue, autoRespawn, autoRespawnAvailable, false, "");
    }

    public CompanionSnapshot(
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
            CompanionVitals vitals,
            List<QueueRow> queue) {
        this(present, operator, companionName, provider, model, thinking, currentTask, history, newLog, perception,
                toolNames, vitals, queue, true, present);
    }
}
