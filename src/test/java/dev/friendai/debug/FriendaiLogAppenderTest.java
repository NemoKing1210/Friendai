package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FriendaiLogAppenderTest {
    private static final class Recall implements LogSink {
        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void accept(LogRecord record) {
            records.add(record);
        }
    }

    private final Recall sink = new Recall();

    @AfterEach
    void detach() {
        LogSinkHub.remove(sink);
    }

    private static LogEvent event(String logger, Level level, String message, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(logger)
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .build();
    }

    @Test
    void anErrorWithAThrowableBecomesAStructuredRecord() {
        LogSinkHub.add(sink);
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.append(event("friendai/task", Level.ERROR, "task broke", new IllegalStateException("bad")));
        assertEquals(1, sink.records.size());
        LogRecord record = sink.records.get(0);
        assertEquals(LogCategory.TASK, record.category());
        assertEquals(LogLevel.ERROR, record.level());
        assertEquals(LogEvents.SERVER_ERROR, record.event());
        assertEquals("java.lang.IllegalStateException", record.error().type());
        assertTrue(record.error().stack().contains("FriendaiLogAppenderTest"));
        assertTrue(record.error().frames() > 0);
    }

    @Test
    void infoAndOtherLoggersAreIgnored() {
        LogSinkHub.add(sink);
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.append(event("friendai/task", Level.INFO, "quiet", null));
        appender.append(event("other", Level.ERROR, "not ours", null));
        assertTrue(sink.records.isEmpty());
    }

    @Test
    void thePlainModLoggerMapsToServerAndWarnMapsToAWarnEvent() {
        LogSinkHub.add(sink);
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.append(event("friendai", Level.WARN, "slow", null));
        assertEquals(1, sink.records.size());
        assertEquals(LogCategory.SERVER, sink.records.get(0).category());
        assertEquals(LogLevel.WARN, sink.records.get(0).level());
        assertEquals(LogEvents.SERVER_WARN, sink.records.get(0).event());
    }

    @Test
    void aLongMessageIsCapped() {
        LogSinkHub.add(sink);
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.append(event("friendai/store", Level.ERROR, "x".repeat(2000), null));
        assertEquals(400, sink.records.get(0).message().length());
    }

    @Test
    void aThrowingSinkDoesNotEscapeTheAppender() throws InterruptedException {
        CountDownLatch second = new CountDownLatch(1);
        LogSinkHub.add(record -> {
            throw new IllegalStateException("sink down");
        });
        LogSinkHub.add(record -> second.countDown());
        FriendaiLogAppender appender = new FriendaiLogAppender();
        appender.append(event("friendai/task", Level.ERROR, "still delivered", null));
        assertTrue(second.await(1, TimeUnit.SECONDS));
        assertNotNull(appender);
    }
}
