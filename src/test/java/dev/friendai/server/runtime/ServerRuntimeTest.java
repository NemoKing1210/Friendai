package dev.friendai.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerRuntimeTest {

    @Test
    void repeatedStopAndCloseCallbacksFlushOnceAndContinueAfterMemoryFailure() {
        AtomicInteger ticks = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        List<String> closed = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        RuntimeResources resources = new RuntimeResources(failures::add);
        resources.onClose(() -> {
            closed.add("memory");
            throw new IllegalStateException("memory flush failed");
        });
        resources.onClose(() -> closed.add("trading"));
        ServerRuntime runtime = new ServerRuntime(ticks::incrementAndGet, stops::incrementAndGet, resources);

        runtime.tick();
        runtime.stopAcceptingWork();
        runtime.stopAcceptingWork();
        runtime.tick();
        runtime.close();
        runtime.close();

        assertEquals(1, ticks.get());
        assertEquals(1, stops.get());
        assertEquals(List.of("trading", "memory"), closed);
        assertEquals(1, failures.size());
        assertEquals("memory flush failed", failures.getFirst().getMessage());
    }
}
