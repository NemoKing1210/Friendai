package dev.friendai.presentation.companion;

import dev.friendai.execution.history.TaskRecord;
import dev.friendai.foundation.diagnostics.LogEntry;
import java.util.ArrayList;
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
 * @param bondTier the bond tier in words, lower case, empty when there is no bond yet
 * @param bondReasons at most a few remembered reasons for the bond, newest first
 * @param goalText the owner's primary goal in the companion's own words, empty when there is none
 * @param goalCriteria every criterion of that goal, one line each
 * @param goalUnmet the subset of {@code goalCriteria} the world does not confirm yet
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
        String freeTimePlannerModel,
        String bondTier,
        List<String> bondReasons,
        String goalText,
        List<String> goalCriteria,
        List<String> goalUnmet,
        boolean workPaused) {
    public static final int MAX_ACTIVITY = 200;
    public static final int MAX_NEXT = 160;
    public static final int MAX_BUDGET = 80;
    public static final int MAX_MODEL = 120;
    public static final int MAX_BOND_TIER = 24;
    public static final int MAX_LIST_ENTRIES = 6;
    public static final int MAX_LIST_TEXT = 160;

    /** The shape before the paused flag, so a reader that predates it keeps compiling. */
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
            String freeTimeLast,
            String freeTimePlannerModel,
            String bondTier,
            List<String> bondReasons,
            String goalText,
            List<String> goalCriteria,
            List<String> goalUnmet) {
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
                freeTimePlannerModel,
                bondTier,
                bondReasons,
                goalText,
                goalCriteria,
                goalUnmet,
                false);
    }

    public CompanionSnapshot {
        freeTimeActivity = cut(freeTimeActivity, MAX_ACTIVITY);
        freeTimeNext = cut(freeTimeNext, MAX_NEXT);
        freeTimeBudget = cut(freeTimeBudget, MAX_BUDGET);
        freeTimeLast = cut(freeTimeLast, MAX_ACTIVITY);
        freeTimePlannerModel = cut(freeTimePlannerModel, MAX_MODEL);
        bondTier = cut(bondTier, MAX_BOND_TIER);
        bondReasons = cut(bondReasons);
        goalText = cut(goalText, MAX_LIST_TEXT);
        goalCriteria = cut(goalCriteria);
        goalUnmet = cut(goalUnmet);
    }

    /** Every shorter constructor ends here, so a caller that knows nothing about the feelings block stays unchanged. */
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
            String freeTimeLast,
            String freeTimePlannerModel) {
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
                freeTimePlannerModel,
                "",
                List.of(),
                "",
                List.of(),
                List.of(),
                false);
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

    /** A bounded copy: at most {@link #MAX_LIST_ENTRIES} entries, each cut to {@link #MAX_LIST_TEXT} characters. */
    private static List<String> cut(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> bounded = new ArrayList<>(Math.min(values.size(), MAX_LIST_ENTRIES));
        for (String value : values) {
            bounded.add(cut(value, MAX_LIST_TEXT));
            if (bounded.size() == MAX_LIST_ENTRIES) {
                break;
            }
        }
        return List.copyOf(bounded);
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
