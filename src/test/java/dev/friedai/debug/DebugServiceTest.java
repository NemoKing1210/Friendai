package dev.friedai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DebugServiceTest {
    private final DebugService service = new DebugService(UnaryOperator.identity(), () -> 0L);

    @Test
    void subscribeResetsLastSent() {
        UUID a = UUID.randomUUID();
        service.subscribe(a);
        service.markSent(a, 42);
        assertEquals(42, service.lastSent(a));
        service.subscribe(a);
        assertEquals(0, service.lastSent(a));
        assertEquals(Set.of(a), service.subscribers());
        service.unsubscribe(a);
        assertTrue(service.subscribers().isEmpty());
    }

    @Test
    void perOwnerIsolation() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertSame(service.logOf(a), service.logOf(a));
        assertNotSame(service.logOf(a), service.logOf(b));
        service.logOf(a).add(LogKind.USER, "x");
        assertTrue(service.logOf(b).after(0, 10).isEmpty());
        assertNotSame(service.historyOf(a), service.historyOf(b));
    }
}
