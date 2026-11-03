package dev.friendai.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.LogContext;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DiagnosticToolExecutorTest {
    private static final List<ToolSpec> SPECS = List.of();
    private static final LogContext OBSERVED =
            new LogContext("t-0001", 4L, 0L, 0L, 0L, 0L, 0L, 0L, "mine_block_at", 9L);

    private static class RecordingExecutor implements ToolExecutor {
        final List<ToolCall> calls = new ArrayList<>();
        final List<AccessLevel> specAccess = new ArrayList<>();
        ToolResult next = ToolResult.ok("done");

        @Override
        public List<ToolSpec> specs(AccessLevel access) {
            specAccess.add(access);
            return SPECS;
        }

        @Override
        public ToolResult execute(ToolCall call, Caller caller) {
            calls.add(call);
            return next;
        }
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, null, arguments, null);
    }

    @Test
    void runsOneCallUnderTheObservedContextAndRestoresThePreviousScope() {
        RecordingExecutor inner = new RecordingExecutor();
        List<LogContext> scopes = new ArrayList<>();
        List<LogContext> activeDuringCall = new ArrayList<>();
        LogContext[] current = {LogContext.NONE};
        DiagnosticToolExecutor executor = new DiagnosticToolExecutor(inner, id -> OBSERVED, (context, body) -> {
            scopes.add(context);
            LogContext previous = current[0];
            current[0] = context;
            try {
                ToolResult result = body.get();
                activeDuringCall.add(current[0]);
                return result;
            } finally {
                current[0] = previous;
            }
        });

        ToolResult result = executor.execute(call("call-1", "mine_block_at", "{}"), (Caller) null);

        assertEquals("done", result.content());
        assertEquals(1, inner.calls.size());
        assertEquals(List.of(OBSERVED), scopes);
        assertEquals(List.of(OBSERVED), activeDuringCall);
        assertEquals(LogContext.NONE, current[0], "the scope must be restored after the call");
    }

    @Test
    void aResultIsForwardedUnchangedWithoutASecondExecution() {
        RecordingExecutor inner = new RecordingExecutor();
        inner.next = ToolResult.error("you do not have authority for that");
        DiagnosticToolExecutor executor =
                new DiagnosticToolExecutor(inner, id -> OBSERVED, (context, body) -> body.get());

        ToolResult result = executor.execute(call("call-2", "mine_block_at", "{not json"), (Caller) null);

        assertTrue(result.error());
        assertEquals("you do not have authority for that", result.content());
        assertEquals(1, inner.calls.size());
    }

    @Test
    void anUnknownCallIdRunsUnderADetachedRootInsteadOfAnotherCallsContext() {
        RecordingExecutor inner = new RecordingExecutor();
        List<LogContext> scopes = new ArrayList<>();
        DiagnosticToolExecutor executor = new DiagnosticToolExecutor(inner, id -> LogContext.NONE, (context, body) -> {
            scopes.add(context);
            return body.get();
        });

        executor.execute(call("unknown", "give_items", "{}"), (Caller) null);

        assertEquals(List.of(LogContext.NONE), scopes);
        assertEquals(1, inner.calls.size());
    }

    @Test
    void specsAndAccessPassThroughForEveryLevel() {
        RecordingExecutor inner = new RecordingExecutor();
        DiagnosticToolExecutor executor =
                new DiagnosticToolExecutor(inner, id -> OBSERVED, (context, body) -> body.get());

        assertSame(SPECS, executor.specs(AccessLevel.OWNER));
        assertSame(SPECS, executor.specs(AccessLevel.GUEST));
        assertEquals(List.of(AccessLevel.OWNER, AccessLevel.GUEST), inner.specAccess);
        assertFalse(inner.specAccess.isEmpty());
    }

    @Test
    void aThrowingCallStillRestoresTheScope() {
        RecordingExecutor inner = new RecordingExecutor() {
            @Override
            public ToolResult execute(ToolCall call, Caller caller) {
                super.execute(call, caller);
                throw new IllegalStateException("tool exploded");
            }
        };
        LogContext[] current = {LogContext.NONE};
        DiagnosticToolExecutor executor = new DiagnosticToolExecutor(inner, id -> OBSERVED, (context, body) -> {
            LogContext previous = current[0];
            current[0] = context;
            try {
                return body.get();
            } finally {
                current[0] = previous;
            }
        });

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> executor.execute(call("call-3", "mine_block_at", "{}"), (Caller) null));

        assertEquals(LogContext.NONE, current[0]);
    }

    @Test
    void aNullContextFunctionAndScopeStillRunTheCallOnce() {
        RecordingExecutor inner = new RecordingExecutor();
        DiagnosticToolExecutor executor =
                new DiagnosticToolExecutor(inner, id -> LogContext.NONE, (context, body) -> body.get());
        Supplier<ToolResult> never = () -> ToolResult.ok("unused");

        assertSame(never.get().content(), "unused");
        assertEquals(
                "done",
                executor.execute(call("call-4", "mine_block_at", "{}"), (Caller) null)
                        .content());
        assertEquals(1, inner.calls.size());
    }
}
