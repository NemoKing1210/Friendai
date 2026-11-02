package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.LogCategory;

/** Maps a logger name to the subsystem it reports about. */
public final class LogCategories {
    private LogCategories() {}

    public static LogCategory of(String loggerName) {
        if (loggerName == null) return null;
        return switch (loggerName) {
            case "friendai/task" -> LogCategory.TASK;
            case "friendai/store" -> LogCategory.SERVER;
            case "friendai/trading" -> LogCategory.TASK;
            case "friendai/brain" -> LogCategory.BRAIN;
            case "friendai/tts" -> LogCategory.SPEECH;
            case "friendai/config" -> LogCategory.CONFIG;
            case "friendai/places" -> LogCategory.WORLD;
            case "friendai/obtain" -> LogCategory.WORLD;
            case "friendai" -> LogCategory.SERVER;
            default -> null;
        };
    }
}
