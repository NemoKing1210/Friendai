package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;

/** Passive view of what the brain does, for debugging. Implementations must be cheap and must not throw. */
public interface BrainObserver {
    BrainObserver NONE = new BrainObserver() {};

    /** A turn began: the batch was chosen and the request is about to be built. */
    default void onTurnStart(long turn) {}

    /** A turn ended with one of `delivered`, `error` or `cancelled`. */
    default void onTurnEnd(long turn, String outcome) {}

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

    /** The exact request handed to the gateway, after routing: system prompt, history and offered tools. */
    default void onRequest(
            String system,
            java.util.List<dev.friendai.agent.llm.model.Message> messages,
            java.util.List<dev.friendai.agent.llm.model.ToolSpec> tools) {}

    /** One provider reply: text, tool calls, finish reason and usage. Not a retry or an error. */
    default void onResponse(dev.friendai.agent.llm.model.LlmResponse response) {}

    default void onError(Throwable error) {}

    /** One measured HTTP attempt of a logical request; safe metadata only, never a body or a header. */
    default void onRequestAttempt(long requestId, dev.friendai.agent.llm.RequestAttempt attempt) {}

    /** A late reply or attempt of a request whose turn already moved on; it had no speech or tool effect. */
    default void onRequestDiscarded(long requestId, String reason) {}

    /** The turn was routed: mode, tool groups and whether a plan belongs to it. Once per turn, before any request. */
    default void onRouteDecision(dev.friendai.agent.route.RouteDecision decision) {}

    /** One planner consultation: the request, then its outcome. The subject is logged by kind and id only. */
    default void onPlannerAttempt(PlannerAttempt attempt) {}
}
