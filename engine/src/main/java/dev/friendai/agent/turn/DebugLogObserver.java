package dev.friendai.agent.turn;

import dev.friendai.agent.llm.RequestAttempt;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.foundation.diagnostics.DebugLog;
import dev.friendai.foundation.diagnostics.LogCategory;
import dev.friendai.foundation.diagnostics.LogContext;
import dev.friendai.foundation.diagnostics.LogError;
import dev.friendai.foundation.diagnostics.LogEventData;
import dev.friendai.foundation.diagnostics.LogEvents;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.diagnostics.LogLevel;
import dev.friendai.foundation.diagnostics.LogPipeline;
import dev.friendai.foundation.diagnostics.LogRecord;
import java.util.Map;
import java.util.UUID;

/**
 * Writes what the brain does: structured records through the pipeline (ring and files), or the historical plain text
 * when constructed with a {@link DebugLog}. Instruction-skill arguments and results are never written.
 */
public final class DebugLogObserver implements BrainObserver {
    private static final int MAX_STACK_FRAMES = 24;

    private final DebugLog legacy;
    private final UUID owner;
    private final UUID companion;
    private final String session;
    private final LogPipeline pipeline;
    private final String trace;
    private volatile ToolCallSnapshot lastToolCall;
    private long turn;
    private long turnStartSeq;
    private long lastRequestSeq;
    private long lastToolCallSeq;

    /** The historical form: plain text into one ring buffer. */
    public DebugLogObserver(DebugLog log) {
        this.legacy = log;
        this.owner = null;
        this.companion = null;
        this.session = "";
        this.pipeline = null;
        this.trace = opaqueTrace();
    }

    public DebugLogObserver(UUID owner, UUID companion, String session, LogPipeline pipeline) {
        this(owner, companion, session, pipeline, opaqueTrace());
    }

    /** {@code trace} is the opaque session token every record and invocation of this brain carries. */
    public DebugLogObserver(UUID owner, UUID companion, String session, LogPipeline pipeline, String trace) {
        this.legacy = null;
        this.owner = owner;
        this.companion = companion;
        this.session = session == null ? "" : session;
        this.pipeline = pipeline;
        this.trace = trace == null || trace.isBlank() ? opaqueTrace() : trace;
    }

    /** The captured context of the tool call just observed, so the executor can run under it. */
    public LogContext contextForToolCall(String callId) {
        String id = callId == null ? "" : callId;
        ToolCallSnapshot snapshot = lastToolCall;
        if (!id.isEmpty() && snapshot != null && id.equals(snapshot.id())) {
            return snapshot.context();
        }
        // An unknown or mismatched call gets a detached root: never another call's provenance.
        return new LogContext(trace, turn, 0L, 0L, 0L, 0L, 0L, 0L, "", turnStartSeq);
    }

    private static String opaqueTrace() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public void onTurnStart(long turn) {
        this.turn = turn;
        turnStartSeq = emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.TASK,
                LogEvents.TURN_START,
                0L,
                "",
                "turn " + turn,
                Map.of("turn", Long.toString(turn)),
                null);
        if (legacy != null) legacy.add(LogKind.TASK, "[event] turn " + turn + " started");
    }

    @Override
    public void onTurnEnd(long turn, String outcome) {
        String result = outcome == null ? "" : outcome;
        emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.TASK,
                LogEvents.TURN_END,
                turnStartSeq,
                "",
                "turn " + turn + ": " + result,
                Map.of("outcome", result),
                null);
        if (legacy != null) legacy.add(LogKind.TASK, "[event] turn " + turn + " " + result);
    }

    @Override
    public void onEvent(BrainEvent e) {
        if (legacy != null) {
            legacy.add(e instanceof BrainEvent.PlayerSaid ? LogKind.USER : LogKind.TASK, e.toPromptLine());
            return;
        }
        LogKind kind = e instanceof BrainEvent.PlayerSaid ? LogKind.USER : LogKind.TASK;
        emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                kind,
                LogEvents.EVENT_POSTED,
                turnStartSeq,
                "",
                includesBodies() ? e.toPromptLine() : e.getClass().getSimpleName(),
                Map.of(),
                null);
    }

    @Override
    public void onEventDropped(BrainEvent e, String reason) {
        if (legacy != null) {
            legacy.add(
                    e instanceof BrainEvent.PlayerSaid ? LogKind.USER : LogKind.TASK,
                    "dropped (" + reason + "): " + e.toPromptLine());
            return;
        }
        LogKind kind = e instanceof BrainEvent.PlayerSaid ? LogKind.USER : LogKind.TASK;
        emit(
                LogLevel.WARN,
                LogCategory.BRAIN,
                kind,
                LogEvents.EVENT_DROPPED,
                turnStartSeq,
                "",
                "dropped (" + reason + "): "
                        + (includesBodies() ? e.toPromptLine() : e.getClass().getSimpleName()),
                Map.of("reason", reason == null ? "" : reason),
                null);
    }

    @Override
    public void onAssistantText(String text) {
        if (legacy != null) {
            legacy.add(LogKind.ASSISTANT, text);
            return;
        }
        String body = text == null ? "" : text;
        String message = includesBodies() ? body : "reply (" + body.length() + " chars)";
        emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.ASSISTANT,
                LogEvents.ASSISTANT_TEXT,
                lastRequestSeq,
                "",
                message,
                Map.of("chars", Integer.toString(body.length())),
                null);
    }

    @Override
    public void onReply(dev.friendai.execution.tool.Caller caller, String text) {
        if (legacy != null) {
            legacy.add(LogKind.ASSISTANT, "delivered: " + text);
            return;
        }
        String body = text == null ? "" : text;
        String message = includesBodies() ? "delivered: " + body : "delivered (" + body.length() + " chars)";
        emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.ASSISTANT,
                LogEvents.ASSISTANT_DELIVERED,
                lastRequestSeq,
                "",
                message,
                Map.of("chars", Integer.toString(body.length())),
                null);
    }

    @Override
    public void onToolCall(ToolCall call) {
        String name = call.name() == null ? "" : call.name();
        boolean instruction = name.equals("manage_skill") || name.equals("use_skill");
        if (legacy != null) {
            legacy.add(
                    LogKind.TOOL_CALL,
                    name + (instruction ? " [instruction fields omitted]" : " " + call.rawArguments()));
            return;
        }
        String args = call.rawArguments() == null ? "" : call.rawArguments();
        String message =
                instruction ? name + " [instruction fields omitted]" : includesBodies() ? name + " " + args : name;
        String callId = call.id() == null ? "" : call.id();
        lastToolCallSeq = emit(
                LogLevel.INFO,
                LogCategory.TOOL,
                LogKind.TOOL_CALL,
                LogEvents.TOOL_CALL,
                turnStartSeq,
                callId,
                message,
                Map.of("tool", name),
                null);
        lastToolCall = new ToolCallSnapshot(
                callId, new LogContext(trace, turn, 0L, 0L, 0L, 0L, 0L, 0L, name, lastToolCallSeq));
    }

    @Override
    public void onToolResult(ToolCall call, ToolResult result) {
        String name = call.name() == null ? "" : call.name();
        boolean instruction = name.equals("manage_skill") || name.equals("use_skill");
        boolean error = result != null && result.error();
        String content = result == null ? "" : result.content();
        if (legacy != null) {
            legacy.add(
                    LogKind.TOOL_RESULT,
                    name + ": " + (error ? "ERROR " : "") + (instruction ? "[instruction fields omitted]" : content));
            return;
        }
        String message = instruction
                ? name + ": " + (error ? "ERROR " : "ok") + " [instruction fields omitted]"
                : includesBodies()
                        ? name + ": " + (error ? "ERROR " : "") + content
                        : name + ": " + (error ? "ERROR" : "ok") + " (" + content.length() + " chars)";
        emit(
                error ? LogLevel.ERROR : LogLevel.INFO,
                LogCategory.TOOL,
                LogKind.TOOL_RESULT,
                LogEvents.TOOL_RESULT,
                lastToolCallSeq,
                call.id() == null ? "" : call.id(),
                message,
                Map.of("tool", name),
                null);
    }

    @Override
    public void onInstructions(java.util.List<String> ids, int chars, int promptChars, boolean overrun) {
        String message = "instructions: " + (ids == null ? "" : ids) + "; characters=" + chars + "; prompt="
                + promptChars + "; estimated tokens=" + ((chars + 3) / 4) + "; mandatory overrun=" + overrun;
        if (legacy != null) {
            legacy.add(LogKind.TOOL_RESULT, message);
            return;
        }
        emit(
                LogLevel.DEBUG,
                LogCategory.BRAIN,
                LogKind.TOOL_RESULT,
                LogEvents.SKILL_SELECT,
                turnStartSeq,
                "",
                message,
                Map.of("chars", Integer.toString(chars)),
                null);
    }

    @Override
    public void onUsage(dev.friendai.agent.llm.model.Usage usage) {
        String message = "provider usage: " + usage;
        if (legacy != null) {
            legacy.add(LogKind.TOOL_RESULT, message);
            return;
        }
        emit(
                LogLevel.DEBUG,
                LogCategory.BRAIN,
                LogKind.TOOL_RESULT,
                LogEvents.LLM_USAGE,
                lastRequestSeq,
                "",
                message,
                Map.of(),
                null);
    }

    @Override
    public void onRequest(
            String system,
            java.util.List<dev.friendai.agent.llm.model.Message> messages,
            java.util.List<dev.friendai.agent.llm.model.ToolSpec> tools) {
        StringBuilder text = new StringBuilder("request: system=")
                .append(system == null ? 0 : system.length())
                .append(" chars; messages=");
        for (dev.friendai.agent.llm.model.Message message : messages) {
            text.append(message.role())
                    .append(' ')
                    .append(preview(message.text()))
                    .append("; ");
        }
        text.append("tools=")
                .append(tools.stream()
                        .map(dev.friendai.agent.llm.model.ToolSpec::name)
                        .toList());
        int systemChars = system == null ? 0 : system.length();
        int historyChars = 0;
        for (dev.friendai.agent.llm.model.Message message : messages) {
            historyChars += message.text() == null ? 0 : message.text().length();
        }
        int schemaChars = 0;
        for (dev.friendai.agent.llm.model.ToolSpec tool : tools) {
            schemaChars += tool.parametersSchema() == null
                    ? 0
                    : tool.parametersSchema().toString().length();
        }
        if (legacy != null) {
            legacy.add(LogKind.REQUEST, text.toString());
            return;
        }
        lastRequestSeq = emit(
                LogLevel.DEBUG,
                LogCategory.BRAIN,
                LogKind.REQUEST,
                LogEvents.LLM_REQUEST,
                turnStartSeq,
                "",
                text.toString(),
                Map.of(
                        "messages", Integer.toString(messages.size()),
                        "systemChars", Integer.toString(systemChars),
                        "historyChars", Integer.toString(historyChars),
                        "tools", Integer.toString(tools.size()),
                        "schemaChars", Integer.toString(schemaChars)),
                null);
    }

    @Override
    public void onResponse(dev.friendai.agent.llm.model.LlmResponse response) {
        String text = "reply: tools="
                + response.toolCalls().stream().map(ToolCall::name).toList()
                + "; stop=" + response.stopReason()
                + "; usage=" + response.usage();
        if (legacy != null) {
            legacy.add(
                    LogKind.RESPONSE,
                    "reply: text=" + preview(response.text()) + "; tools="
                            + response.toolCalls().stream().map(ToolCall::name).toList()
                            + "; stop=" + response.stopReason()
                            + "; usage=" + response.usage());
            return;
        }
        String message = includesBodies() ? text + "; text=" + preview(response.text()) : text;
        emit(
                LogLevel.DEBUG,
                LogCategory.BRAIN,
                LogKind.RESPONSE,
                LogEvents.LLM_RESPONSE,
                lastRequestSeq,
                "",
                message,
                Map.of("tools", Integer.toString(response.toolCalls().size())),
                null);
    }

    @Override
    public void onRequestAttempt(long requestId, RequestAttempt attempt) {
        if (legacy != null || attempt == null) return;
        Map<String, String> facts = new java.util.LinkedHashMap<>();
        facts.put("request", Long.toString(requestId));
        facts.put("attempt", Integer.toString(attempt.attempt()));
        facts.put("stage", attempt.stage());
        if (attempt.status() > 0) facts.put("status", Integer.toString(attempt.status()));
        if (!attempt.errorKind().isEmpty()) facts.put("error", attempt.errorKind());
        if (attempt.durationMs() >= 0L) facts.put("durationMs", Long.toString(attempt.durationMs()));
        if (attempt.backoffMs() > 0L) facts.put("backoffMs", Long.toString(attempt.backoffMs()));
        if (attempt.payloadBytes() > 0L) facts.put("bytes", Long.toString(attempt.payloadBytes()));
        emit(
                attempt.retry() ? LogLevel.WARN : LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.REQUEST,
                attempt.retry() ? LogEvents.LLM_RETRY : LogEvents.LLM_ATTEMPT,
                lastRequestSeq,
                "",
                "request " + requestId + " attempt " + attempt.attempt() + " " + attempt.stage(),
                facts,
                null);
    }

    @Override
    public void onRequestDiscarded(long requestId, String reason) {
        if (legacy != null) return;
        emit(
                LogLevel.INFO,
                LogCategory.BRAIN,
                LogKind.REQUEST,
                LogEvents.LLM_DISCARDED,
                lastRequestSeq,
                "",
                "request " + requestId + " discarded",
                Map.of("request", Long.toString(requestId), "reason", reason == null ? "" : reason),
                null);
    }

    @Override
    public void onError(Throwable error) {
        String type = error == null ? "" : error.getClass().getName();
        String message = error == null ? "" : String.valueOf(error);
        if (legacy != null) {
            legacy.add(LogKind.ERROR, message);
            return;
        }
        emit(
                LogLevel.ERROR,
                LogCategory.BRAIN,
                LogKind.ERROR,
                LogEvents.LLM_ERROR,
                lastRequestSeq,
                "",
                message,
                Map.of(),
                new LogError(type, message, stack(error), frames(error)));
    }

    private boolean includesBodies() {
        return pipeline != null && pipeline.includesBodies();
    }

    private long emit(
            LogLevel level,
            LogCategory category,
            LogKind kind,
            String event,
            long cause,
            String toolCall,
            String message,
            Map<String, String> data,
            LogError error) {
        if (pipeline == null) return 0L;
        Map<String, String> facts = data;
        if (!trace.isEmpty()) {
            facts = LogEventData.build(new LogContext(trace, turn, 0L, 0L, 0L, 0L, 0L, 0L, "", cause), data);
        }
        return pipeline.emit(new LogRecord(
                0L, 0L, level, category, kind, event, owner, companion, session, turn, 0L, 0L, 0L, toolCall, cause, -1L,
                message, facts, error));
    }

    private static String preview(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }

    private record ToolCallSnapshot(String id, LogContext context) {}

    private static int frames(Throwable error) {
        return error == null || error.getStackTrace() == null ? 0 : error.getStackTrace().length;
    }

    private static String stack(Throwable error) {
        if (error == null || error.getStackTrace() == null) return "";
        StringBuilder text = new StringBuilder();
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, MAX_STACK_FRAMES); i++) {
            text.append(frames[i]).append('\n');
        }
        return text.toString();
    }
}
