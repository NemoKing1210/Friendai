package dev.friedai.testutil;

import dev.friedai.brain.LlmGateway;
import dev.friedai.llm.model.LlmResponse;
import dev.friedai.llm.model.Message;
import dev.friedai.llm.model.StopReason;
import dev.friedai.llm.model.ToolCall;
import dev.friedai.llm.model.ToolSpec;
import dev.friedai.llm.model.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/** Scripted gateway: queue responses (or leave a future pending) and inspect the requests. */
public final class FakeGateway implements LlmGateway {
    public record Request(String system, List<Message> messages, List<ToolSpec> tools) {}

    private final Queue<CompletableFuture<LlmResponse>> queue = new ArrayDeque<>();
    public final List<Request> requests = new ArrayList<>();

    public static LlmResponse text(String text) {
        return new LlmResponse(text, List.of(), StopReason.END, new Usage(0, 0));
    }

    public static LlmResponse calls(String text, ToolCall... calls) {
        return new LlmResponse(text, List.of(calls), StopReason.TOOL_USE, new Usage(0, 0));
    }

    public static ToolCall call(String id, String name) {
        return new ToolCall(id, name, new com.google.gson.JsonObject(), "{}", null);
    }

    public void enqueue(LlmResponse response) {
        queue.add(CompletableFuture.completedFuture(response));
    }

    public CompletableFuture<LlmResponse> enqueuePending() {
        CompletableFuture<LlmResponse> f = new CompletableFuture<>();
        queue.add(f);
        return f;
    }

    public void enqueueFailure(Throwable t) {
        queue.add(CompletableFuture.failedFuture(t));
    }

    @Override
    public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
        requests.add(new Request(system, new ArrayList<>(messages), tools));
        CompletableFuture<LlmResponse> next = queue.poll();
        return next != null ? next : CompletableFuture.failedFuture(new IllegalStateException("no response queued"));
    }
}
