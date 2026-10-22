package dev.friedai.brain;

import dev.friedai.llm.model.ToolCall;
import dev.friedai.tool.ToolResult;

/** Passive view of what the brain does, for debugging. Implementations must be cheap and must not throw. */
public interface BrainObserver {
    BrainObserver NONE = new BrainObserver() {};

    default void onEvent(BrainEvent e) {}

    default void onAssistantText(String text) {}

    default void onToolCall(ToolCall call) {}

    default void onToolResult(ToolCall call, ToolResult result) {}

    default void onError(Throwable error) {}
}
