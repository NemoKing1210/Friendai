package dev.friendai.agent.llm.stream;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.llm.protocol.ProtocolSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Assembles the authoritative response from streamed increments. Text is passed on to the consumer as it arrives; tool
 * calls are collected by index and only the finished response carries them, because a partial argument string must
 * never reach an executor.
 */
public final class StreamAssembly {
    private static final class Call {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    private final Consumer<StreamDelta> sink;
    private final StringBuilder text = new StringBuilder();
    private final Map<Integer, Call> calls = new TreeMap<>();
    private int inputTokens;
    private int outputTokens;
    private StopReason stop;
    private boolean started;
    private boolean terminal;

    public StreamAssembly() {
        this(null);
    }

    public StreamAssembly(Consumer<StreamDelta> sink) {
        this.sink = sink;
    }

    public void text(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        started = true;
        text.append(delta);
        emit(new StreamDelta.Text(delta));
    }

    public void toolCall(int index, String id, String name, String arguments) {
        Call call = calls.computeIfAbsent(index, ignored -> new Call());
        if (id != null && !id.isBlank()) {
            call.id = id;
            started = true;
        }
        if (name != null && !name.isBlank()) {
            call.name = name;
            started = true;
        }
        if (arguments != null && !arguments.isEmpty()) {
            call.arguments.append(arguments);
            started = true;
        }
    }

    public void usage(Integer input, Integer output) {
        if (input != null) inputTokens = input;
        if (output != null) outputTokens = output;
    }

    public void stop(StopReason reason) {
        if (reason != null) {
            stop = reason;
            terminal = true;
        }
    }

    /** The provider closed the stream without naming a stop reason. */
    public void done() {
        terminal = true;
    }

    /** Whether the provider actually streamed something a fallback no longer needs to produce. */
    public boolean started() {
        return started;
    }

    /**
     * Whether the provider said the reply is complete. Content alone is not a terminal state: a body that ended after
     * a text or tool-call fragment may have been cut off, and treating it as the answer would speak or run half of one.
     */
    public boolean terminal() {
        return terminal;
    }

    public LlmResponse response() {
        List<ToolCall> tools = new ArrayList<>(calls.size());
        for (Map.Entry<Integer, Call> entry : calls.entrySet()) {
            Call call = entry.getValue();
            String raw = call.arguments.toString();
            tools.add(new ToolCall(
                    call.id.isBlank() ? ProtocolSupport.callId(entry.getKey()) : call.id,
                    call.name,
                    ProtocolSupport.parseArguments(raw),
                    raw,
                    null));
        }
        StopReason reason = stop != null ? stop : tools.isEmpty() ? StopReason.END : StopReason.TOOL_USE;
        return new LlmResponse(text.toString(), tools, reason, new Usage(inputTokens, outputTokens));
    }

    private void emit(StreamDelta delta) {
        if (sink != null) {
            sink.accept(delta);
        }
    }
}
