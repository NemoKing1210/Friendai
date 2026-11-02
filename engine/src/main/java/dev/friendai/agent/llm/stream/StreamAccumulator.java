package dev.friendai.agent.llm.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Assembled view of one incremental response. Text is kept in order; tool-call fragments are joined per provider index
 * and only exposed after the stream finishes. Sentence release is deliberately conservative: a sentence is offered to
 * the caller only at its terminator, so a guard sees the whole sentence even when its words arrived in separate chunks.
 * Any increment after {@link StreamDelta.Done} or {@link #stop()} is ignored.
 */
public final class StreamAccumulator {
    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, Builder> calls = new TreeMap<>();
    private int inputTokens = -1;
    private int outputTokens = -1;
    private int released;
    private boolean done;
    private boolean stopped;

    /** Applies one increment; terminal increments close the accumulation. */
    public void accept(StreamDelta delta) {
        if (delta == null || done || stopped) {
            return;
        }
        switch (delta) {
            case StreamDelta.Text value -> text.append(value.text());
            case StreamDelta.ToolCall value ->
                calls.computeIfAbsent(value.index(), ignored -> new Builder()).merge(value);
            case StreamDelta.Usage value -> {
                if (value.inputTokens() != null) inputTokens = value.inputTokens();
                if (value.outputTokens() != null) outputTokens = value.outputTokens();
            }
            case StreamDelta.Done ignored -> done = true;
        }
    }

    public String text() {
        return text.toString();
    }

    public boolean done() {
        return done;
    }

    public boolean stopped() {
        return stopped;
    }

    /** Assembles tool calls in provider index order; malformed JSON keeps the raw text and a null arguments object. */
    public List<ToolCall> toolCalls() {
        List<ToolCall> assembled = new ArrayList<>(calls.size());
        for (Map.Entry<Integer, Builder> entry : calls.entrySet()) {
            Builder builder = entry.getValue();
            if (builder.name.isBlank()) {
                continue;
            }
            String raw = builder.arguments.toString();
            JsonObject arguments = null;
            try {
                if (!raw.isBlank()) {
                    arguments = JsonParser.parseString(raw).getAsJsonObject();
                }
            } catch (RuntimeException malformed) {
                arguments = null;
            }
            assembled.add(new ToolCall(builder.id, builder.name, arguments, raw, ""));
        }
        return List.copyOf(assembled);
    }

    /** The complete response; a tool call wins over the caller's fallback stop reason. */
    public LlmResponse response(StopReason fallback) {
        List<ToolCall> toolCalls = toolCalls();
        StopReason reason = toolCalls.isEmpty() ? fallback : StopReason.TOOL_USE;
        Usage usage = inputTokens < 0 && outputTokens < 0
                ? null
                : new Usage(Math.max(0, inputTokens), Math.max(0, outputTokens));
        return new LlmResponse(text.toString(), toolCalls, reason, usage);
    }

    /**
     * Releases complete sentences that the guard accepts. The unterminated tail stays buffered, so a guard never sees
     * half a sentence. At the end of the stream the caller may call {@link #flushRemaining(Predicate)}.
     */
    public List<String> releaseSentences(Predicate<String> safe) {
        if (stopped || done) {
            return List.of();
        }
        List<String> releasedSentences = new ArrayList<>();
        int boundary;
        while ((boundary = nextBoundary(released)) >= 0) {
            String sentence = text.substring(released, boundary + 1).strip();
            released = boundary + 1;
            if (!sentence.isEmpty() && (safe == null || safe.test(sentence))) {
                releasedSentences.add(sentence);
            }
        }
        return releasedSentences;
    }

    /** Offers the final unterminated sentence once at end of stream; afterwards nothing is released. */
    public List<String> flushRemaining(Predicate<String> safe) {
        if (stopped) {
            return List.of();
        }
        String tail = text.substring(released).strip();
        released = text.length();
        if (tail.isEmpty() || (safe != null && !safe.test(tail))) {
            return List.of();
        }
        return List.of(tail);
    }

    /** Stops releasing text and tool calls; a late increment is dropped. */
    public void stop() {
        stopped = true;
    }

    private int nextBoundary(int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static final class Builder {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();

        void merge(StreamDelta.ToolCall value) {
            if (id.isEmpty()) id = value.id();
            if (name.isEmpty()) name = value.name();
            arguments.append(value.arguments());
        }
    }
}
