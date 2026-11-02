package dev.friendai.debug;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogError;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Feeds `friendai*` logger warnings and errors into the structured log, with the throwable and its category. It
 * never logs and never throws; a failing sink is swallowed by {@link LogSinkHub}.
 */
public final class FriendaiLogAppender extends AbstractAppender {
    public static final String NAME = "friendai-structured";
    private static final int MAX_FRAMES = 24;
    private static boolean installed;

    public FriendaiLogAppender() {
        super(NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    /** Registers the appender once per process; a second call is a no-op. */
    public static synchronized void install() {
        if (installed) return;
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.start();
        context.getConfiguration().addAppender(appender);
        context.getRootLogger().addAppender(appender);
        context.updateLoggers();
        installed = true;
    }

    @Override
    public void append(LogEvent event) {
        if (event == null) return;
        String loggerName = event.getLoggerName();
        if (!structured(loggerName)) return;
        LogLevel level = level(event.getLevel());
        if (level == null) return;
        LogCategory category = LogCategories.of(loggerName);
        if (category == null) category = LogCategory.SERVER;
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (message.length() > dev.friendai.foundation.diagnostics.LogLimits.MAX_MESSAGE_CHARS) {
            message = message.substring(0, dev.friendai.foundation.diagnostics.LogLimits.MAX_MESSAGE_CHARS - 1) + "…";
        }
        Throwable thrown = event.getThrown();
        LogError error = thrown == null
                ? null
                : new LogError(
                        thrown.getClass().getName(),
                        String.valueOf(thrown.getMessage()),
                        stack(thrown, MAX_FRAMES),
                        thrown.getStackTrace() == null ? 0 : thrown.getStackTrace().length);
        LogSinkHub.accept(new LogRecord(
                0L,
                event.getTimeMillis(),
                level,
                category,
                null,
                level == LogLevel.ERROR ? LogEvents.SERVER_ERROR : LogEvents.SERVER_WARN,
                null,
                null,
                "",
                0L,
                0L,
                0L,
                0L,
                "",
                0L,
                -1L,
                message == null ? "" : message,
                Map.of("logger", loggerName == null ? "" : loggerName),
                error));
    }

    private static boolean structured(String loggerName) {
        if (loggerName == null) return false;
        return loggerName.equals("friendai") || loggerName.startsWith("friendai/");
    }

    private static LogLevel level(Level level) {
        if (level == null) return null;
        if (level.intLevel() <= Level.ERROR.intLevel()) return LogLevel.ERROR;
        if (level.intLevel() <= Level.WARN.intLevel()) return LogLevel.WARN;
        return null;
    }

    private static String stack(Throwable thrown, int maxFrames) {
        if (thrown.getStackTrace() == null) return "";
        StringBuilder text = new StringBuilder();
        StackTraceElement[] frames = thrown.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, maxFrames); i++) {
            text.append(frames[i]).append('\n');
        }
        return text.toString();
    }
}
