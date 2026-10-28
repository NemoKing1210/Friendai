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
}
