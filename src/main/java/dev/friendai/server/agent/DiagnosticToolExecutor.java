package dev.friendai.server.agent;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.turn.DebugLogObserver;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.ToolExecutor;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.server.execution.ExecutionCoordinator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs each tool call under the diagnostic context of the call the observer just saw, then restores the previous
 * scope. Specs, access and results pass through unchanged: a diagnostic wrapper never grants authority, changes a
 * result or executes a call twice.
 */
public final class DiagnosticToolExecutor implements ToolExecutor {
    private final ToolExecutor inner;
    private final Function<String, LogContext> contextFor;
    private final BiFunction<LogContext, Supplier<ToolResult>, ToolResult> scope;

    public DiagnosticToolExecutor(ToolExecutor inner, DebugLogObserver observer, ExecutionCoordinator coordinator) {
        this(
                inner,
                observer == null ? callId -> LogContext.NONE : observer::contextForToolCall,
                coordinator == null ? (context, body) -> body.get() : coordinator::withLogContext);
    }

    public DiagnosticToolExecutor(
            ToolExecutor inner,
            Function<String, LogContext> contextFor,
            BiFunction<LogContext, Supplier<ToolResult>, ToolResult> scope) {
        this.inner = inner;
        this.contextFor = contextFor == null ? callId -> LogContext.NONE : contextFor;
        this.scope = scope == null ? (context, body) -> body.get() : scope;
    }

    @Override
    public List<ToolSpec> specs(AccessLevel access) {
        return inner.specs(access);
    }

    @Override
    public ToolResult execute(ToolCall call, Caller caller) {
        if (call == null) return inner.execute(null, caller);
        LogContext context = contextFor.apply(call.id());
        return scope.apply(context, () -> inner.execute(call, caller));
    }
}
