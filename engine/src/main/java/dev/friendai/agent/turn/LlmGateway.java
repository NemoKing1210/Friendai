package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolSpec;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What the brain needs from an LLM: one completion for a system prompt, history and tools. */
public interface LlmGateway {
    CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools);

    /**
     * The same completion with the prompt split into the text that survives a turn and the text that does not. Only a
     * protocol-backed gateway can place a cache breakpoint at that boundary; every other gateway sends {@link
     * dev.friendai.agent.llm.model.SystemPrompt#text()} and keeps its old behaviour.
     */
    default CompletableFuture<LlmResponse> complete(
            dev.friendai.agent.llm.model.SystemPrompt system, List<Message> messages, List<ToolSpec> tools) {
        return complete(system.text(), messages, tools);
    }

    /**
     * The tokens the configured model accepts in one request, or 0 when this gateway cannot say. The brain then uses a
     * conservative fallback, so an unknown provider only shortens a long history.
     */
    default int contextTokens() {
        return 0;
    }

    /** The tokens reserved for the reply, or 0 when the profile does not state a limit. */
    default int maxOutputTokens() {
        return 0;
    }

    /** Requests a JSON object when the gateway supports it; older and fake gateways remain compatible. */
    default CompletableFuture<LlmResponse> completeJson(String system, List<Message> messages, int outputMultiplier) {
        return complete(system, messages, List.of());
    }

    /** The same completion with a per-request telemetry scope; older and fake gateways keep their behaviour. */
    default CompletableFuture<LlmResponse> complete(
            String system,
            List<Message> messages,
            List<ToolSpec> tools,
            dev.friendai.agent.llm.RequestTelemetry telemetry) {
        return complete(system, messages, tools);
    }

    /** The split prompt with a telemetry scope. */
    default CompletableFuture<LlmResponse> complete(
            dev.friendai.agent.llm.model.SystemPrompt system,
            List<Message> messages,
            List<ToolSpec> tools,
            dev.friendai.agent.llm.RequestTelemetry telemetry) {
        return complete(system.text(), messages, tools, telemetry);
    }

    default CompletableFuture<LlmResponse> completeJson(
            String system,
            List<Message> messages,
            int outputMultiplier,
            dev.friendai.agent.llm.RequestTelemetry telemetry) {
        return completeJson(system, messages, outputMultiplier);
    }
}
