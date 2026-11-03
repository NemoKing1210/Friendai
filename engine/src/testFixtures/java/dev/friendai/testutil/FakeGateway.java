package dev.friendai.testutil;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import dev.friendai.agent.turn.LlmGateway;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Scripted gateway: queue responses (or leave a future pending) and inspect the requests.
 *
 * <p>Besides the request list it records the payload metrics the evaluation baseline reports: system prompt
 * characters, history messages and characters, and tool spec count and UTF-8 bytes. Usage comes from the response
 * envelopes; every scripted response carries {@code Usage(0, 0)}, so its totals are synthetic and must never be read
 * as provider spend. A completion is recorded once by a single callback, so a streamed usage update cannot be added
 * twice.
 */
public final class FakeGateway implements LlmGateway {

    /** Observable payload of one request, measured after routing and before it leaves the brain. */
    public record Payload(int systemChars, int historyMessages, int historyChars, int toolSpecs, int toolSpecBytes) {
        public static final Payload ZERO = new Payload(0, 0, 0, 0, 0);

        public Payload plus(Payload other) {
            return new Payload(
                    systemChars + other.systemChars,
                    historyMessages + other.historyMessages,
                    historyChars + other.historyChars,
                    toolSpecs + other.toolSpecs,
                    toolSpecBytes + other.toolSpecBytes);
        }
    }

    /** Usage observed on completed responses: {@code synthetic} is true for every scripted envelope. */
    public record UsageTotal(boolean synthetic, Integer inputTokens, Integer outputTokens, int sources) {}

    public record Request(String system, List<Message> messages, List<ToolSpec> tools) {
        public Payload payload() {
            return measure(system, messages, tools);
        }
    }

    public record JsonRequest(String system, List<Message> messages, int outputMultiplier) {}

    private final Queue<CompletableFuture<LlmResponse>> queue = new ArrayDeque<>();
    private final Set<CompletableFuture<LlmResponse>> pending = Collections.newSetFromMap(new IdentityHashMap<>());
    public final List<Request> requests = new ArrayList<>();
    public final List<JsonRequest> jsonRequests = new ArrayList<>();

    private int failedCompletions;
    private int cancelledCompletions;
    private int retries;
    private boolean previousCompletionFailed;
    private int usageSources;
    private long inputTokens;
    private long outputTokens;

    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0));
    }

    public static LlmResponse calls(String text, ToolCall... calls) {
        return new LlmResponse(text, List.of(calls), StopReason.TOOL_USE, new Usage(0, 0));
    }

    public static ToolCall call(String id, String name) {
        return new ToolCall(id, name, new com.google.gson.JsonObject(), "{}", null);
    }

    /** Measures the exact strings the gateway receives, without touching the brain. */
    public static Payload measure(String system, List<Message> messages, List<ToolSpec> tools) {
        int systemChars = system == null ? 0 : system.length();
        int historyMessages = messages == null ? 0 : messages.size();
        int historyChars = 0;
        if (messages != null) {
            for (Message message : messages) {
                historyChars += message.text() == null ? 0 : message.text().length();
                for (ToolCall call : message.toolCalls()) {
                    historyChars += length(call.name()) + length(call.rawArguments()) + length(call.signature());
                }
            }
        }
        int toolSpecs = tools == null ? 0 : tools.size();
        int toolSpecBytes = 0;
        if (tools != null) {
            for (ToolSpec spec : tools) {
                toolSpecBytes += utf8(spec.name())
                        + utf8(spec.description())
                        + utf8(
                                spec.parametersSchema() == null
                                        ? ""
                                        : spec.parametersSchema().toString());
            }
        }
        return new Payload(systemChars, historyMessages, historyChars, toolSpecs, toolSpecBytes);
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static int utf8(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    public void enqueue(LlmResponse response) {
        queue.add(CompletableFuture.completedFuture(response));
    }

    public CompletableFuture<LlmResponse> enqueuePending() {
        CompletableFuture<LlmResponse> f = new CompletableFuture<>();
        pending.add(f);
        queue.add(f);
        return f;
    }

    public void enqueueFailure(Throwable t) {
        queue.add(CompletableFuture.failedFuture(t));
    }

    @Override
    public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
        synchronized (this) {
            if (previousCompletionFailed) {
                retries++;
                previousCompletionFailed = false;
            }
            requests.add(new Request(system, new ArrayList<>(messages), tools));
            CompletableFuture<LlmResponse> next = queue.poll();
            if (next == null) {
                previousCompletionFailed = true;
                return CompletableFuture.failedFuture(new IllegalStateException("no response queued"));
            }
            next.whenComplete((response, error) -> record(next, response, error));
            return next;
        }
    }

    @Override
    public CompletableFuture<LlmResponse> completeJson(String system, List<Message> messages, int outputMultiplier) {
        jsonRequests.add(new JsonRequest(system, new ArrayList<>(messages), outputMultiplier));
        return complete(system, messages, List.of());
    }

    /** One terminal transition of a queued completion; the single place usage and failure counters move. */
    private synchronized void record(CompletableFuture<LlmResponse> future, LlmResponse response, Throwable error) {
        pending.remove(future);
        if (future.isCancelled()) {
            cancelledCompletions++;
            previousCompletionFailed = true;
            return;
        }
        if (error != null) {
            failedCompletions++;
            previousCompletionFailed = true;
            return;
        }
        if (response != null && response.usage() != null) {
            usageSources++;
            inputTokens += response.usage().inputTokens();
            outputTokens += response.usage().outputTokens();
        }
    }

    /** Payload of every request made through this gateway, summed. Empty requests give {@link Payload#ZERO}. */
    public synchronized Payload payload() {
        Payload total = Payload.ZERO;
        for (Request request : requests) {
            total = total.plus(request.payload());
        }
        return total;
    }

    /**
     * Usage the scripted responses carried. Values are {@code null} when no completed response carried usage at all;
     * that is unknown, not a measured zero. Scripted usage is always flagged synthetic.
     */
    public synchronized UsageTotal usageTotal() {
        if (usageSources == 0) {
            return new UsageTotal(true, null, null, 0);
        }
        return new UsageTotal(true, (int) inputTokens, (int) outputTokens, usageSources);
    }

    /** Requests whose completion failed with an error (not a cancellation). */
    public synchronized int failedCompletions() {
        return failedCompletions;
    }

    /** Completions whose future was cancelled before a response arrived. */
    public synchronized int cancelledCompletions() {
        return cancelledCompletions;
    }

    /** Requests issued after a failed or cancelled completion: the retry count, by request. */
    public synchronized int retries() {
        return retries;
    }

    /** Pending completions still waiting for a response. */
    public synchronized int pendingCompletions() {
        return pending.size();
    }
}
