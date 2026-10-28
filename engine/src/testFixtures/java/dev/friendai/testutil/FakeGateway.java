package dev.friendai.testutil;

import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.StopReason;
import dev.friendai.agent.llm.model.ToolCall;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.model.Usage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/** Scripted gateway: queue responses (or leave a future pending) and inspect the requests. */
public final class FakeGateway implements LlmGateway {
    public record Request(String system, List<Message> messages, List<ToolSpec> tools) {}

    public record JsonRequest(String system, List<Message> messages, int outputMultiplier) {}

    private final Queue<CompletableFuture<LlmResponse>> queue = new ArrayDeque<>();
    public final List<Request> requests = new ArrayList<>();
    public final List<JsonRequest> jsonRequests = new ArrayList<>();

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

    @Override
    public CompletableFuture<LlmResponse> completeJson(String system, List<Message> messages, int outputMultiplier) {
        jsonRequests.add(new JsonRequest(system, new ArrayList<>(messages), outputMultiplier));
        return complete(system, messages, List.of());
    }
}
