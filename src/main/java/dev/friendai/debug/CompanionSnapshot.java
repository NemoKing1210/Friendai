package dev.friendai.debug;

import java.util.List;

/** What the server tells a menu client about its companion. Log, perception and tool names are operator-only. */
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
        List<QueueRow> queue) {}
