package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogRecord;
import dev.friendai.foundation.diagnostics.LogSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LogSinkHubTest {
    private final List<LogSink> attached = new ArrayList<>();

    private static LogRecord record() {
        return new LogRecord(
                1L,
                1L,
                LogLevel.INFO,
                LogCategory.SERVER,
                null,
                "server.start",
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
                "hi",
                Map.of(),
                null);
    }

    private void attach(LogSink sink) {
        LogSinkHub.add(sink);
        attached.add(sink);
    }

    @AfterEach
    void detach() {
        for (LogSink sink : attached) {
            LogSinkHub.remove(sink);
        }
        attached.clear();
    }

    @Test
    void everyAttachedSinkReceivesTheRecord() {
        List<LogRecord> first = new ArrayList<>();
        List<LogRecord> second = new ArrayList<>();
        attach(first::add);
        attach(second::add);
        LogSinkHub.accept(record());
        assertEquals(1, first.size());
        assertEquals(1, second.size());
    }

    @Test
    void aRemovedSinkStopsReceiving() {
        List<LogRecord> first = new ArrayList<>();
        LogSink sink = first::add;
        attach(sink);
        LogSinkHub.remove(sink);
        attached.remove(sink);
        LogSinkHub.accept(record());
        assertTrue(first.isEmpty());
    }

    @Test
    void aThrowingSinkDoesNotStopTheOthers() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        attach(record -> {
            throw new IllegalStateException("down");
        });
        attach(record -> delivered.countDown());
        LogSinkHub.accept(record());
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
    }

    @Test
    void concurrentAttachRemoveAndAcceptDoNotThrow() throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                LogSink sink = record -> {};
                for (int j = 0; j < 100; j++) {
                    LogSinkHub.add(sink);
                    LogSinkHub.accept(record());
                    LogSinkHub.remove(sink);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5000L);
        }
        assertTrue(threads.stream().noneMatch(Thread::isAlive));
    }
}
