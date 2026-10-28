package dev.friendai.debug;

import java.util.List;

/**
 * What the server tells a menu client about its companion. Log, perception and tool names are operator-only.
 *
 * @param freeTimeOn the companion is working on its own
 * @param freeTimeActivity what it is doing in free time, empty between activities
 * @param freeTimeWaiting free time is on and a director turn is choosing the next activity
 * @param followMode whether the companion walks to its owner on its own initiative
 * @param freeTimeNext the next decision free time will take, in words
 * @param freeTimeBudget the director budget and backoff
 * @param freeTimeLast the last finished shift and its result
 * @param freeTimePlannerModel the effective model selected for free-time plan drafting
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
    public static final int MAX_ACTIVITY = 200;
    public static final int MAX_NEXT = 160;
    public static final int MAX_BUDGET = 80;
    public static final int MAX_MODEL = 120;

    public CompanionSnapshot {
        freeTimeActivity = cut(freeTimeActivity, MAX_ACTIVITY);
        freeTimeNext = cut(freeTimeNext, MAX_NEXT);
        freeTimeBudget = cut(freeTimeBudget, MAX_BUDGET);
        freeTimeLast = cut(freeTimeLast, MAX_ACTIVITY);
        freeTimePlannerModel = cut(freeTimePlannerModel, MAX_MODEL);
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
            boolean autoRespawnAvailable,
            boolean creativeMode,
            boolean creativeModeAvailable,
            boolean freeTimeOn,
            String freeTimeActivity,
            boolean freeTimeWaiting,
            boolean followMode,
            String freeTimeNext,
            String freeTimeBudget,
            String freeTimeLast) {
        this(
                present,
                operator,
                companionName,
                provider,
                model,
                thinking,
                currentTask,
                history,
                newLog,
                perception,
                toolNames,
                vitals,
                queue,
                autoRespawn,
                autoRespawnAvailable,
                creativeMode,
                creativeModeAvailable,
                freeTimeOn,
                freeTimeActivity,
                freeTimeWaiting,
                followMode,
                freeTimeNext,
                freeTimeBudget,
                freeTimeLast,
                "");
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, max) : text;
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
            boolean autoRespawnAvailable,
            boolean creativeMode,
            boolean creativeModeAvailable,
            boolean freeTimeOn,
            String freeTimeActivity,
            boolean freeTimeWaiting,
            boolean followMode) {
        this(
                present,
                operator,
                companionName,
                provider,
                model,
                thinking,
                currentTask,
                history,
                newLog,
                perception,
                toolNames,
                vitals,
                queue,
                autoRespawn,
                autoRespawnAvailable,
                creativeMode,
                creativeModeAvailable,
                freeTimeOn,
                freeTimeActivity,
                freeTimeWaiting,
                followMode,
                "",
                "",
                "");
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
            boolean autoRespawnAvailable,
            boolean creativeMode,
            boolean creativeModeAvailable) {
        this(
                present,
                operator,
                companionName,
                provider,
                model,
                thinking,
                currentTask,
                history,
                newLog,
                perception,
                toolNames,
                vitals,
                queue,
                autoRespawn,
                autoRespawnAvailable,
                creativeMode,
                creativeModeAvailable,
                false,
                "",
                false,
                true,
                "",
                "",
                "");
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
        this(
                present,
                operator,
                companionName,
                provider,
                model,
                thinking,
                currentTask,
                history,
                newLog,
                perception,
                toolNames,
                vitals,
                queue,
                autoRespawn,
                autoRespawnAvailable,
                false,
                false,
                false,
                "",
                false,
                true,
                "",
                "",
                "");
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
        this(
                present,
                operator,
                companionName,
                provider,
                model,
                thinking,
                currentTask,
                history,
                newLog,
                perception,
                toolNames,
                vitals,
                queue,
                true,
                present,
                false,
                false,
                false,
                "",
                false,
                true,
                "",
                "",
                "");
    }
}
