package dev.friendai.client.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.client.runtime.ClientLifecycle;
import dev.friendai.presentation.companion.ClientSessionId;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WhisperLocalSttTest {
    @Test
    void disconnectKeepsAcceptedTranscriptionContextAliveUntilTheQueuedWorkFinishes() {
        var backend = new FakeBackend();
        var executor = new ManualExecutorService();
        var stt = new WhisperLocalStt(Path.of("fake-model.bin"), backend, executor);
        var lifecycle = new ClientLifecycle();
        ClientSessionId session = lifecycle.onConnect();
        lifecycle.ownSession(session, stt);
        var deliveredResults = new ArrayList<String>();

        var result = stt.transcribe(new float[] {0.25f, -0.25f}, "en");
        result.thenAccept(text -> {
            if (lifecycle.accepts(session)) deliveredResults.add(text);
        });
        lifecycle.onDisconnect(session);
        lifecycle.onDisconnect(session);

        assertFalse(stt.isReady());
        executor.runAll();

        assertEquals("recognized speech", result.join());
        assertTrue(deliveredResults.isEmpty());
        assertEquals(List.of("transcribe", "close"), backend.operations);
        assertSame(backend.context, backend.transcribedContext);
        assertSame(backend.context, backend.closedContext);
        assertEquals(1, backend.closeCount.get());
    }

    private static final class FakeBackend implements WhisperLocalStt.Backend {
        private final Object context = new Object();
        private final List<String> operations = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();
        private Object transcribedContext;
        private Object closedContext;

        @Override
        public Object open(Path model) {
            assertEquals(Path.of("fake-model.bin"), model);
            return context;
        }

        @Override
        public String transcribe(Object actualContext, float[] samples16k, String language) {
            transcribedContext = actualContext;
            assertEquals(2, samples16k.length);
            assertEquals("en", language);
            operations.add("transcribe");
            return "recognized speech";
        }

        @Override
        public void close(Object actualContext) {
            closedContext = actualContext;
            closeCount.incrementAndGet();
            operations.add("close");
        }
    }

    private static final class ManualExecutorService extends AbstractExecutorService {
        private final Queue<Runnable> queue = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var pending = new ArrayList<>(queue);
            queue.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queue.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new IllegalStateException("executor is shut down");
            queue.add(command);
        }

        void runAll() {
            Runnable next;
            while ((next = queue.poll()) != null) next.run();
        }
    }
}
