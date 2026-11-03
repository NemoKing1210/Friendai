package dev.friendai.agent.turn;

import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.model.Message;
import dev.friendai.agent.llm.model.ToolSpec;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What the brain needs from an LLM: one completion for a system prompt, history and tools. */
public interface LlmGateway {
    CompletableFuture<LlmResponse> complete(String system, List<Message> messages, List<ToolSpec> tools);

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

    default CompletableFuture<LlmResponse> completeJson(
            String system,
            List<Message> messages,
            int outputMultiplier,
            dev.friendai.agent.llm.RequestTelemetry telemetry) {
        return completeJson(system, messages, outputMultiplier);
    }
}
