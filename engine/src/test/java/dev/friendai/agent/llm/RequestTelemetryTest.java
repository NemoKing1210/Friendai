package dev.friendai.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestTelemetryTest {
    @Test
    void theScopeCarriesItsIdentityToEveryAttempt() {
        List<RequestAttempt> attempts = new ArrayList<>();
        RequestTelemetry telemetry = new RequestTelemetry(7L, 3L, 2, attempts::add);
        telemetry.accept(new RequestAttempt(1, "start", 0, "", -1L, 0L, 120L));

        assertEquals(7L, telemetry.requestId());
        assertEquals(3L, telemetry.turn());
        assertEquals(2, telemetry.generation());
        assertEquals(1, attempts.size());
    }

    @Test
    void aThrowingObserverNeverEscapes() {
        RequestTelemetry telemetry = new RequestTelemetry(1L, 0L, 0, attempt -> {
            throw new IllegalStateException("observer broke");
        });

        telemetry.accept(new RequestAttempt(1, "start", 0, "", -1L, 0L, 0L));
    }

    @Test
    void noTelemetryIsANoop() {
        RequestTelemetry.NONE.accept(new RequestAttempt(1, "finish", 200, "", 5L, 0L, 10L));
        assertEquals(0L, RequestTelemetry.NONE.requestId());
    }

    @Test
    void anAttemptNormalizesUnknownValues() {
        RequestAttempt attempt = new RequestAttempt(2, null, 0, null, -5L, -2L, -3L);

        assertEquals(RequestAttempt.START, attempt.stage());
        assertEquals("", attempt.errorKind());
        assertEquals(-1L, attempt.durationMs());
        assertEquals(0L, attempt.backoffMs());
        assertEquals(0L, attempt.payloadBytes());
        assertTrue(!attempt.retry());
        assertTrue(new RequestAttempt(1, RequestAttempt.RETRY, 0, "", 0L, 5L, 0L).retry());
    }
}
