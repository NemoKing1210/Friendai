package dev.friedai.debug;

import dev.friedai.brain.BrainEvent;
import dev.friedai.brain.BrainObserver;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.tool.ToolResult;

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
    public void onAssistantText(String text) {
        log.add(LogKind.ASSISTANT, text);
    }

    @Override
    public void onToolCall(ToolCall call) {
        log.add(LogKind.TOOL_CALL, call.name() + " " + call.rawArguments());
    }

    @Override
    public void onToolResult(ToolCall call, ToolResult result) {
        log.add(LogKind.TOOL_RESULT, call.name() + ": " + (result.error() ? "ERROR " : "") + result.content());
    }

    @Override
    public void onError(Throwable error) {
        log.add(LogKind.ERROR, error.toString());
    }
}
