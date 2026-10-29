package dev.friendai.client.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.presentation.companion.ClientSessionId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AudioSessionLifecycleTest {
    @Test
    void disconnectClosesActiveAudioResourcesOnceAndRejectsLateResults() {
        ClientLifecycle lifecycle = new ClientLifecycle();
        ClientSessionId session = lifecycle.onConnect();
        List<AtomicInteger> resourceCloses = new ArrayList<>();
        List<String> deliveredResults = new ArrayList<>();
        List<Runnable> lateResults = new ArrayList<>();

        for (String stage : List.of("recording", "transcribing", "synthesizing", "playing")) {
            AtomicInteger closes = new AtomicInteger();
            resourceCloses.add(closes);
            lifecycle.ownSession(session, closes::incrementAndGet);
            lateResults.add(() -> {
                if (lifecycle.accepts(session)) deliveredResults.add(stage);
            });
        }

        lifecycle.onDisconnect(session);
        lifecycle.onDisconnect(session);
        lateResults.forEach(Runnable::run);

        assertEquals(
                List.of(1, 1, 1, 1),
                resourceCloses.stream().map(AtomicInteger::get).toList());
        assertFalse(lifecycle.accepts(session));
        assertTrue(deliveredResults.isEmpty());
    }

    @Test
    void staleCallbacksAndLateResourceRegistrationCannotAffectTheNextSession() {
        ClientLifecycle lifecycle = new ClientLifecycle();
        ClientSessionId first = lifecycle.onConnect();
        AtomicInteger firstResourceCloses = new AtomicInteger();
        lifecycle.ownSession(first, firstResourceCloses::incrementAndGet);

        ClientSessionId second = lifecycle.onConnect();
        AtomicInteger lateResourceCloses = new AtomicInteger();
        lifecycle.ownSession(first, lateResourceCloses::incrementAndGet);

        assertNotEquals(first, second);
        assertFalse(lifecycle.accepts(first));
        assertTrue(lifecycle.accepts(second));
        assertEquals(1, firstResourceCloses.get());
        assertEquals(1, lateResourceCloses.get());
        lifecycle.onDisconnect(first);
        assertTrue(lifecycle.accepts(second));
    }

    @Test
    void applicationDownloadsOutliveDisconnectAndCloseAtClientShutdown() {
        ClientLifecycle lifecycle = new ClientLifecycle();
        AtomicInteger downloadCloses = new AtomicInteger();
        ClientSessionId session = lifecycle.onConnect();
        lifecycle.ownApplication(downloadCloses::incrementAndGet);

        lifecycle.onDisconnect(session);
        assertEquals(0, downloadCloses.get());

        lifecycle.close();
        lifecycle.close();
        assertEquals(1, downloadCloses.get());
        assertFalse(lifecycle.accepts(session));
    }

    @Test
    void oneBrokenResourceDoesNotSkipTheRestOfDisconnectCleanup() {
        AtomicInteger reportedFailures = new AtomicInteger();
        ClientLifecycle lifecycle = new ClientLifecycle(failure -> reportedFailures.incrementAndGet());
        ClientSessionId session = lifecycle.onConnect();
        AtomicInteger laterResourceCloses = new AtomicInteger();
        lifecycle.ownSession(session, laterResourceCloses::incrementAndGet);
        lifecycle.ownSession(session, () -> {
            throw new IllegalStateException("device already closed");
        });

        lifecycle.onDisconnect(session);

        assertEquals(1, reportedFailures.get());
        assertEquals(1, laterResourceCloses.get());
        assertFalse(lifecycle.accepts(session));
    }

    @Test
    void duplicateOwnershipOfOneSessionResourceStillClosesItOnlyOnce() {
        ClientLifecycle lifecycle = new ClientLifecycle();
        ClientSessionId session = lifecycle.onConnect();
        AtomicInteger closes = new AtomicInteger();
        AutoCloseable resource = closes::incrementAndGet;
        lifecycle.ownSession(session, resource);
        lifecycle.ownSession(session, resource);

        lifecycle.onDisconnect(session);

        assertEquals(1, closes.get());
    }
}
