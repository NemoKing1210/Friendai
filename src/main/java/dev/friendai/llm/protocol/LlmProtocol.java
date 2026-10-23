package dev.friendai.llm.protocol;

import dev.friendai.llm.model.LlmRequest;
import dev.friendai.llm.model.LlmResponse;
import dev.friendai.llm.provider.Endpoint;
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
