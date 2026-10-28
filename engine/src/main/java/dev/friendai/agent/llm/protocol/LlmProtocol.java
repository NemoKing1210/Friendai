package dev.friendai.agent.llm.protocol;

import dev.friendai.agent.llm.model.LlmRequest;
import dev.friendai.agent.llm.model.LlmResponse;
import dev.friendai.agent.llm.provider.Endpoint;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmProtocol {
    String id();

    CompletableFuture<LlmResponse> complete(Endpoint endpoint, LlmRequest request);

    CompletableFuture<List<String>> listModels(Endpoint endpoint);

    default boolean supportsTools() {
        return true;
    }
}
