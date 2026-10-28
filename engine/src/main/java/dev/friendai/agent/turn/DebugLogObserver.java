package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogKind;

/** Writes what the brain does into a {@link DebugLog}. */
public final class DebugLogObserver implements BrainObserver {
    private final DebugLog log;

    public DebugLogObserver(DebugLog log) {
        this.log = log;
    }

    @Override
    public void onEvent(BrainEvent e) {
        LogKind kind = e instanceof BrainEvent.PlayerSaid ? LogKind.USER : LogKind.TASK;
        log.add(kind, e.toPromptLine());
    }

    @Override
    public void onEventDropped(BrainEvent e, String reason) {
        log.add(LogKind.USER, "dropped (" + reason + "): " + e.toPromptLine());
    }

    @Override
    public void onAssistantText(String text) {
        log.add(LogKind.ASSISTANT, text);
    }

    @Override
    public void onToolCall(ToolCall call) {
        log.add(
                LogKind.TOOL_CALL,
                call.name()
                        + (call.name().equals("manage_skill") || call.name().equals("use_skill")
                                ? " [instruction fields omitted]"
                                : " " + call.rawArguments()));
    }

    @Override
    public void onToolResult(ToolCall call, ToolResult result) {
        log.add(
                LogKind.TOOL_RESULT,
                call.name() + ": " + (result.error() ? "ERROR " : "")
                        + (call.name().equals("manage_skill") || call.name().equals("use_skill")
                                ? "[instruction fields omitted]"
                                : result.content()));
    }

    @Override
    public void onInstructions(java.util.List<String> ids, int chars, int promptChars, boolean overrun) {
        log.add(
                LogKind.TOOL_RESULT,
                "Instructions: " + ids + "; characters=" + chars + "; prompt=" + promptChars
                        + "; estimated instruction tokens=" + ((chars + 3) / 4) + "; mandatory overrun=" + overrun);
    }

    @Override
    public void onUsage(dev.friendai.agent.llm.model.Usage usage) {
        log.add(LogKind.TOOL_RESULT, "Provider token usage: " + usage);
    }

    @Override
    public void onError(Throwable error) {
        log.add(LogKind.ERROR, error.toString());
    }
}
