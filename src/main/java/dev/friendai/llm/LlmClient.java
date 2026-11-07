package dev.friendai.llm;

import com.google.gson.JsonObject;
import dev.friendai.agent.llm.RequestTelemetry;
import dev.friendai.agent.llm.StreamingHttpTransport;
import dev.friendai.agent.llm.model.LlmErrorKind;
import dev.friendai.agent.llm.model.LlmException;
import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolSpec;
import dev.friendai.agent.llm.protocol.ProtocolSupport;
import dev.friendai.agent.llm.protocol.StreamingProtocol;
import dev.friendai.agent.llm.provider.LlmProfile;
import dev.friendai.agent.llm.provider.ProviderRegistry;
import dev.friendai.agent.llm.provider.Route;
import dev.friendai.agent.llm.stream.SseFrames;
import dev.friendai.agent.llm.stream.StreamAssembly;
import dev.friendai.agent.llm.stream.StreamDelta;
import dev.friendai.agent.llm.stream.StreamingLlmGateway;
import dev.friendai.agent.turn.LlmGateway;
import dev.friendai.foundation.text.UserMessage;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** The configured LLM: resolves the route for the profile and sends requests through it. */
public final class LlmClient implements LlmGateway, StreamingLlmGateway {
    private final ProviderRegistry registry;
    private final LlmProfile profile;
    /** Null when the caller did not build a streaming transport; the ordinary path is then used unchanged. */
    private final StreamingHttpTransport streaming;

    public LlmClient(ProviderRegistry registry, LlmProfile profile) {
        this(registry, profile, null);
    }

    public LlmClient(ProviderRegistry registry, LlmProfile profile, StreamingHttpTransport streaming) {
        this.registry = registry;
        this.profile = profile;
        this.streaming = streaming;
    }

    @Override
    public CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools) {
        return complete(system, messages, tools, false, profile.maxTokens(), RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<LlmResponse> completeJson(String system, List<Message> messages, int outputMultiplier) {
        return complete(
                system,
                messages,
                List.of(),
                true,
                multipliedMaxTokens(profile.maxTokens(), outputMultiplier),
                RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<LlmResponse> complete(
            String system, List<Message> messages, List<ToolSpec> tools, RequestTelemetry telemetry) {
        return complete(system, messages, tools, false, profile.maxTokens(), telemetry);
    }

    @Override
    public CompletableFuture<LlmResponse> completeJson(
            String system, List<Message> messages, int outputMultiplier, RequestTelemetry telemetry) {
        return complete(
                system,
                messages,
                List.of(),
                true,
                multipliedMaxTokens(profile.maxTokens(), outputMultiplier),
                telemetry);
    }

    @Override
    public CompletableFuture<LlmResponse> completeStreaming(
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            java.util.function.Consumer<StreamDelta> onDelta) {
        return completeStreaming(system, messages, tools, onDelta, RequestTelemetry.NONE);
    }

    @Override
    public CompletableFuture<LlmResponse> completeStreaming(
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            java.util.function.Consumer<StreamDelta> onDelta,
            RequestTelemetry telemetry) {
        try {
            Route route = registry.resolve(profile);
            if (!(streaming != null && route.protocol() instanceof StreamingProtocol protocol)) {
                return complete(system, messages, tools, false, profile.maxTokens(), telemetry);
            }
            LlmRequest request = new LlmRequest(
                    profile.model(),
                    system,
                    messages,
                    tools,
                    profile.temperature(),
                    profile.maxTokens(),
                    false,
                    telemetry == null ? RequestTelemetry.NONE : telemetry);
            SseFrames frames = new SseFrames();
            StreamAssembly assembly = new StreamAssembly(onDelta);
            CompletableFuture<LlmResponse> result = new CompletableFuture<>();
            // The ordinary request a refused stream falls back to, so cancelling the caller cancels it too.
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<LlmResponse>> fallback =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CompletableFuture<Void> body = streaming.postStream(
                    ProtocolSupport.uri(route.endpoint(), protocol.streamingPath()),
                    ProtocolSupport.headers(route.endpoint()),
                    protocol.streamingBody(request),
                    bytes -> {
                        for (SseFrames.Frame frame : frames.feed(bytes)) {
                            protocol.onFrame(frame, assembly);
                        }
                    },
                    telemetry == null ? RequestTelemetry.NONE : telemetry);
            body.whenComplete((ignored, error) -> {
                try {
                    // A stop wins over every late frame, including the one that refills the assembled response.
                    if (result.isCancelled()) return;
                    for (SseFrames.Frame frame : frames.finish()) {
                        protocol.onFrame(frame, assembly);
                    }
                    if (assembly.started() && assembly.terminal()) {
                        // The assembled response is authoritative; deltas only told the turn what to say early.
                        if (onDelta != null) onDelta.accept(new StreamDelta.Done());
                        result.complete(assembly.response());
                        return;
                    }
                    if (!assembly.started()) {
                        // Nothing usable streamed: one ordinary request, exactly as a non-streaming profile does.
                        fallback(system, messages, tools, telemetry, result, fallback);
                        return;
                    }
                    // Something streamed but the provider never closed the reply: a partial answer is not an answer.
                    result.completeExceptionally(new LlmException(
                            LlmErrorKind.PARSE, "the streamed reply ended before the provider finished it"));
                } catch (RuntimeException broken) {
                    // A faulty terminal frame or a throwing delta consumer must still settle the caller's future.
                    result.completeExceptionally(broken);
                }
            });
            result.whenComplete((response, error) -> {
                if (result.isCancelled()) {
                    body.cancel(true);
                    CompletableFuture<LlmResponse> active = fallback.get();
                    if (active != null) active.cancel(true);
                }
            });
            return result;
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** One ordinary request when the stream carried nothing usable; the caller's cancel reaches it. */
    private void fallback(
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            RequestTelemetry telemetry,
            CompletableFuture<LlmResponse> result,
            java.util.concurrent.atomic.AtomicReference<CompletableFuture<LlmResponse>> active) {
        if (result.isCancelled()) return;
        CompletableFuture<LlmResponse> request =
                complete(system, messages, tools, false, profile.maxTokens(), telemetry);
        active.set(request);
        if (result.isCancelled()) {
            // Cancelled between the check above and the hand-over: the fallback must not outlive its caller.
            request.cancel(true);
            return;
        }
        request.whenComplete((response, error) -> {
            if (error != null) result.completeExceptionally(error);
            else result.complete(response);
        });
    }

    private CompletableFuture<LlmResponse> complete(
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            boolean jsonObject,
            int maxTokens,
            RequestTelemetry telemetry) {
        try {
            Route route = registry.resolve(profile);
            LlmRequest request = new LlmRequest(
                    profile.model(),
                    system,
                    messages,
                    tools,
                    profile.temperature(),
                    maxTokens,
                    jsonObject,
                    telemetry == null ? RequestTelemetry.NONE : telemetry);
            return route.protocol().complete(route.endpoint(), request);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static int multipliedMaxTokens(int maxTokens, int multiplier) {
        long product = (long) Math.max(0, maxTokens) * Math.max(1, multiplier);
        return (int) Math.min(Integer.MAX_VALUE, product);
    }

    public CompletableFuture<List<String>> listModels() {
        try {
            Route route = registry.resolveModels(profile);
            return route.protocol().listModels(route.endpoint());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** Sends one request with a single tool and reports whether the model called it. */
    public CompletableFuture<UserMessage> testConnection() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        ToolSpec ping = new ToolSpec("ping", "Connectivity check. Call this tool.", schema);
        return complete(
                        "You are a connectivity test. Call the ping tool.",
                        List.of(Message.user("ping")),
                        List.of(ping),
                        false,
                        profile.maxTokens(),
                        RequestTelemetry.NONE)
                .thenApply(r -> r.toolCalls().isEmpty()
                        ? UserMessage.of("friendai.config.test.ok_text")
                        : UserMessage.of("friendai.config.test.ok_tools"));
    }
}
