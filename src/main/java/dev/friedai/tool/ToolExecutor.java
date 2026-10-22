package dev.friedai.tool;

import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import java.util.List;

/** What the brain sees of the tools: their specs and a way to run a call. Never throws. */
public interface ToolExecutor {
    List<ToolSpec> specs();

    ToolResult execute(ToolCall call);
}
