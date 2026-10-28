package dev.friendai.execution.tool;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import java.util.List;

/** What the brain sees of the tools: their specs and a way to run a call. Never throws. */
public interface ToolExecutor {
    List<ToolSpec> specs(AccessLevel access);

    ToolResult execute(ToolCall call, Caller caller);

    /** All tools, regardless of access. */
    default List<ToolSpec> specs() {
        return specs(AccessLevel.OWNER);
    }

    /** Runs for an anonymous caller at this level: manual runs and older call sites. */
    default ToolResult execute(ToolCall call, AccessLevel access) {
        return execute(call, new Caller(null, "", access));
    }

    /** Runs with owner rights: manual runs and existing callers. */
    default ToolResult execute(ToolCall call) {
        return execute(call, AccessLevel.OWNER);
    }
}
