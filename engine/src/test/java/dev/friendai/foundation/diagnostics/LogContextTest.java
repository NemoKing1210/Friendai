package dev.friendai.foundation.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LogContextTest {
    @Test
    void noneContributesNoIdentityKeys() {
        assertTrue(LogContext.NONE.data().isEmpty());
    }

    @Test
    void dataCarriesOnlyTheKeysWithoutATopLevelField() {
        LogContext context = new LogContext("t-0001", 4L, 5L, 6L, 7L, 8L, 9L, 10L, "call-1", 11L);

        assertEquals(Map.of("trace", "t-0001", "execution", "8", "span", "9", "parentSpan", "10"), context.data());
        assertFalse(context.data().containsKey("turn"));
        assertFalse(context.data().containsKey("toolCall"));
        assertFalse(context.data().containsKey("cause"));
    }

    @Test
    void zeroIdentifiersStayUnknownInsteadOfBeingWrittenAsZero() {
        LogContext context = new LogContext("", 0L, 0L, 0L, 0L, 3L, 0L, 0L, "", 0L);

        assertEquals(Map.of("execution", "3"), context.data());
    }

    @Test
    void traceIsBoundedToThirtyTwoCharacters() {
        assertEquals(
                32,
                new LogContext("t".repeat(80), 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", 0L)
                        .trace()
                        .length());
        assertEquals("", new LogContext("  ", 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", 0L).trace());
    }

    @Test
    void withExecutionKeepsTheCapturedTraceAndToolCall() {
        LogContext original = new LogContext("t-0001", 4L, 0L, 0L, 0L, 0L, 0L, 0L, "call-1", 9L);

        LogContext next = original.withExecution(12L, 3L, 5L, 6L);

        assertEquals("t-0001", next.trace());
        assertEquals("call-1", next.toolCall());
        assertEquals(9L, next.cause());
        assertEquals(12L, next.execution());
        assertEquals(3L, next.task());
        assertEquals(5L, next.plan());
        assertEquals(6L, next.job());
        assertNotSame(original, next);
        assertEquals(0L, original.execution());
    }

    @Test
    void withSpanAndCauseAreIndependentCopies() {
        LogContext original = new LogContext("t-0001", 1L, 0L, 0L, 0L, 2L, 0L, 0L, "", 0L);

        LogContext spanned = original.withSpan(4L, 2L, 17L);
        LogContext caused = original.withCause(18L);

        assertEquals(4L, spanned.span());
        assertEquals(2L, spanned.parentSpan());
        assertEquals(17L, spanned.cause());
        assertEquals(0L, original.span());
        assertEquals(18L, caused.cause());
        assertEquals(0L, original.cause());
    }

    @Test
    void theDataViewCannotBeMutatedByAProducer() {
        Map<String, String> data = new LogContext("t-0001", 0L, 0L, 0L, 0L, 1L, 0L, 0L, "", 0L).data();

        assertThrows(UnsupportedOperationException.class, () -> data.put("execution", "99"));
    }
}
