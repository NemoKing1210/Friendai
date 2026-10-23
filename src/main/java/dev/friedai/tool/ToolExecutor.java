package dev.friedai.tool;

import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import java.util.List;

/** What the brain sees of the tools: their specs and a way to run a call. Never throws. */
public interface ToolExecutor {
    List<ToolSpec> specs(AccessLevel access);

    ToolResult execute(ToolCall call, AccessLevel access);

    /** All tools, regardless of access. */
    default List<ToolSpec> specs() {
        return specs(AccessLevel.OWNER);
    }

    /** Runs with owner rights: manual runs and existing callers. */
    default ToolResult execute(ToolCall call) {
        return execute(call, AccessLevel.OWNER);
    }
}
