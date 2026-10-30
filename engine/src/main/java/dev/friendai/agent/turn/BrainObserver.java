package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;

/** Passive view of what the brain does, for debugging. Implementations must be cheap and must not throw. */
public interface BrainObserver {
    BrainObserver NONE = new BrainObserver() {};

    default void onEvent(BrainEvent e) {}

    /** A posted event that never reached the queue, with a short English reason. */
    default void onEventDropped(BrainEvent e, String reason) {}

    default void onAssistantText(String text) {}

    /** The text a player actually heard, after the reply guard; silent and empty lines are not reported. */
    default void onReply(dev.friendai.execution.tool.Caller caller, String text) {}

    default void onToolCall(ToolCall call) {}

    default void onToolResult(ToolCall call, ToolResult result) {}

    default void onInstructions(java.util.List<String> ids, int chars, int promptChars, boolean mandatoryOverrun) {}

    default void onUsage(dev.friendai.agent.llm.model.Usage usage) {}

    default void onError(Throwable error) {}
}
